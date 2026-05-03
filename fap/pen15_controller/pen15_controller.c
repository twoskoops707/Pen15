#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_usb_cdc.h>
#include <furi_hal_subghz.h>
#include <gui/gui.h>
#include <gui/view_port.h>
#include <cli/cli_vcp.h>
#include <storage/storage.h>
#include <lfrfid/lfrfid_worker.h>
#include <lfrfid/protocols/lfrfid_protocols.h>
#include <infrared_worker.h>
#include <infrared.h>
#include <ibutton/ibutton_worker.h>
#include <ibutton/ibutton_protocols.h>
#include <ibutton/ibutton_key.h>
#include <nfc/nfc.h>
#include <nfc/nfc_scanner.h>
#include <nfc/nfc_device.h>
#include <subghz/subghz_worker.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "jsmn.h"

#define TAG           "Pen15"
#define USB_PKT_LEN   64
#define UART_RX_BUF   512
#define JSON_BUF_SZ   2048
#define DISP_STR_LEN  22
#define MAX_TOKENS    64
#define UART_RX_WAIT  500
#define HW_TIMEOUT_MS 30000
#define TX_MAX_TIMES  512
#define RX_MAX_TIMES  64

/* ── Events ───────────────────────────────────────────────────────── */
typedef enum {
    EvtStop       = (1 << 0),
    EvtUsbRx      = (1 << 1),
    EvtUartRx     = (1 << 2),
    EvtHwDone     = (1 << 3),
    EvtTxDone     = (1 << 4),
    EvtBridgeExit = (1 << 5),
} Pen15Evt;
#define ALL_EVENTS (EvtStop | EvtUsbRx | EvtUartRx | EvtHwDone | EvtTxDone | EvtBridgeExit)

/* ── Hardware state ───────────────────────────────────────────────── */
typedef enum {
    HwIdle,
    HwRfidRead,
    HwRfidEmulate,
    HwNfcDetect,
    HwIrRx,
    HwIkeyRead,
    HwIkeyEmulate,
    HwSubghzRx,
    HwSubghzRecord,
    HwSubghzTx,
} HwState;

typedef enum { ModeJson, ModeMenu, ModeBridge } AppMode;

#define MENU_COUNT 9
static const char* MENU_TITLES[MENU_COUNT] __attribute__((unused)) = { "RFID Read", "NFC Detect", "SubGHz RX", "IR Learn", "iButton Read", "SubGHz TX", "UART Bridge", "GPIO Control", "Exit" };
static const char* MENU_HINTS[MENU_COUNT] __attribute__((unused)) = { "READ", "DETECT", "RECORD", "LEARN", "READ", "TX", "BRIDGE", "GPIO", "EXIT" };


typedef enum { PinUnset = 0, PinInput, PinOutput } PinMode;

/* ── App context ──────────────────────────────────────────────────── */
typedef struct {
    FuriThread*          thread;
    FuriMutex*           usb_mtx;
    FuriSemaphore*       tx_sem;
    CliVcp*              cli_vcp;

    FuriHalSerialHandle* serial;
    FuriStreamBuffer*    uart_rx_buf;
    bool                 uart_ready;
    volatile bool        bridge_mode;

    char   json_buf[JSON_BUF_SZ];
    size_t json_len;

    char   status[DISP_STR_LEN];
    char   cmd_disp[DISP_STR_LEN];
    char   detail_disp[DISP_STR_LEN];
    char   rx_disp[DISP_STR_LEN];
    uint8_t progress;
    uint8_t spin;

    PinMode pin_mode[8];

    AppMode    app_mode;
    uint8_t    menu_index;
    uint32_t   bridge_exit_tick;
    bool       init_done;

    Gui*      gui;
    ViewPort* vp;

    /* ── Hardware state ─── */
    HwState  hw_state;
    char     hw_id[16];
    char     hw_result_json[1024];
    uint32_t hw_deadline_tick;

    /* RFID */
    LFRFIDWorker*  rfid_worker;
    ProtocolDict*  rfid_dict;

    /* Infrared RX */
    InfraredWorker* ir_worker;

    /* iButton */
    iButtonWorker*    ibutton_worker;
    iButtonProtocols* ibutton_protocols;
    iButtonKey*       ibutton_key;

    /* NFC */
    Nfc*        nfc;
    NfcScanner* nfc_scanner;

    /* SubGHz RX / Record */
    SubGhzWorker* subghz_worker;
    uint32_t      subghz_rx_count;
    bool          subghz_record_mode;
    int32_t       rx_timings[RX_MAX_TIMES];
    size_t        rx_timings_count;

    /* SubGHz TX */
    int32_t  tx_timings[TX_MAX_TIMES];
    size_t   tx_count;
    size_t   tx_idx;
    int      tx_repeat;
    int      tx_repeat_cnt;

} Pen15App;

/* ── GPIO pin map ─────────────────────────────────────────────────── */
static const GpioPin* const EXT_PINS[8] = {
    &gpio_ext_pa7, &gpio_ext_pa6, &gpio_ext_pa4, &gpio_ext_pb3,
    &gpio_ext_pb2, &gpio_ext_pc3, &gpio_ext_pc1, &gpio_ext_pc0,
};

static const char* SPIN_CHARS[] = {"|", "/", "-", "\\"};

/* ── OOK 650kHz CC1101 preset (FuriHalSubGhzPresetOok650Async) ────── */
static const uint8_t OOK650_PRESET[] = {
    0x02, 0x0D,
    0x03, 0x07,
    0x08, 0x32,
    0x0B, 0x06,
    0x10, 0x17,
    0x11, 0x32,
    0x12, 0x30,
    0x13, 0x00,
    0x14, 0x00,
    0x18, 0x18,
    0x19, 0x18,
    0x1B, 0x07,
    0x1C, 0x00,
    0x1D, 0x91,
    0x20, 0xFB,
    0x21, 0xB6,
    0x22, 0x11,
    0x00, 0x00,
    0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

/* ═══════════════════════════════════════════════════════════════════
   CDC callbacks
   ═══════════════════════════════════════════════════════════════════ */
static void cdc_on_rx(void* ctx) {
    Pen15App* app = ctx;
    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtUsbRx);
}
static void cdc_on_tx_done(void* ctx) {
    Pen15App* app = ctx;
    furi_semaphore_release(app->tx_sem);
}
static void cdc_state_cb(void* ctx, uint8_t s)                   { UNUSED(ctx); UNUSED(s); }
static void cdc_ctrl_cb(void* ctx, uint8_t s) {
    Pen15App* app = ctx;
    if(app->bridge_mode && !(s & 0x01)) {
        app->app_mode = ModeJson;
        app->bridge_mode = false;
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtBridgeExit);
    }
}
static void cdc_cfg_cb(void* ctx, struct usb_cdc_line_coding* c) { UNUSED(ctx); UNUSED(c); }

static const CdcCallbacks CDC_CB = {
    cdc_on_tx_done, cdc_on_rx, cdc_state_cb, cdc_ctrl_cb, cdc_cfg_cb,
};

/* ═══════════════════════════════════════════════════════════════════
   UART RX DMA
   ═══════════════════════════════════════════════════════════════════ */
static void uart_rx_dma_cb(FuriHalSerialHandle* h, FuriHalSerialRxEvent ev,
                            size_t size, void* ctx) {
    Pen15App* app = ctx;
    if(ev & (FuriHalSerialRxEventData | FuriHalSerialRxEventIdle)) {
        uint8_t tmp[FURI_HAL_SERIAL_DMA_BUFFER_SIZE];
        while(size > 0) {
            size_t got = furi_hal_serial_dma_rx(h, tmp, (size > sizeof(tmp)) ? sizeof(tmp) : size);
            furi_stream_buffer_send(app->uart_rx_buf, tmp, got, 0);
            size -= got;
        }
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtUartRx);
    }
}

/* ═══════════════════════════════════════════════════════════════════
   USB send
   ═══════════════════════════════════════════════════════════════════ */
static void usb_send(Pen15App* app, const char* str) {
    uint16_t len = (uint16_t)strlen(str);
    if(len == 0) return;
    furi_semaphore_acquire(app->tx_sem, 300);
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    furi_hal_cdc_send(0, (uint8_t*)str, len);
    furi_mutex_release(app->usb_mtx);
}

static void usb_send_raw(Pen15App* app, const uint8_t* data, uint16_t len) {
    if(len == 0) return;
    furi_semaphore_acquire(app->tx_sem, 300);
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    furi_hal_cdc_send(0, (uint8_t*)data, len);
    furi_mutex_release(app->usb_mtx);
}

static void set_disp(char* dst, size_t dst_sz, const char* src) {
    if(!dst || dst_sz == 0) return;
    if(!src || !src[0]) src = "---";
    strncpy(dst, src, dst_sz - 1);
    dst[dst_sz - 1] = '\0';
}

static void json_escape_copy(char* dst, size_t dst_sz, const char* src) {
    if(!dst || dst_sz == 0) return;
    size_t w = 0;
    if(!src) src = "";
    for(size_t r = 0; src[r] && w + 2 < dst_sz; r++) {
        char c = src[r];
        if(c == '"' || c == '\\') {
            dst[w++] = '\\';
            dst[w++] = c;
        } else if(c == '\r' || c == '\n' || (unsigned char)c < 32) {
            dst[w++] = ' ';
        } else {
            dst[w++] = c;
        }
    }
    dst[w] = '\0';
}

static void ui_state(
    Pen15App* app,
    const char* status,
    const char* action,
    const char* detail,
    const char* evidence,
    uint8_t progress) {
    if(status) set_disp(app->status, sizeof(app->status), status);
    if(action) set_disp(app->cmd_disp, sizeof(app->cmd_disp), action);
    if(detail) set_disp(app->detail_disp, sizeof(app->detail_disp), detail);
    if(evidence) set_disp(app->rx_disp, sizeof(app->rx_disp), evidence);
    app->progress = progress;
    view_port_update(app->vp);
}

static void emit_status(
    Pen15App* app,
    const char* id,
    const char* action,
    const char* status,
    const char* message,
    const char* detail,
    const char* evidence,
    uint8_t progress) {
    char esc_msg[96];
    char esc_detail[96];
    char esc_evidence[96];
    char resp[384];
    json_escape_copy(esc_msg, sizeof(esc_msg), message);
    json_escape_copy(esc_detail, sizeof(esc_detail), detail);
    json_escape_copy(esc_evidence, sizeof(esc_evidence), evidence);
    snprintf(
        resp,
        sizeof(resp),
        "{\"status\":\"%s\",\"action\":\"%s\",\"message\":\"%s\","
        "\"detail\":\"%s\",\"evidence\":\"%s\",\"progress\":%u,\"id\":\"%s\"}\n",
        status ? status : "",
        action ? action : "",
        esc_msg,
        esc_detail,
        esc_evidence,
        progress,
        id ? id : "");
    usb_send(app, resp);
}

/* ═══════════════════════════════════════════════════════════════════
   GUI
   ═══════════════════════════════════════════════════════════════════ */
static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2,  10, "PEN15 CTRL");
    canvas_draw_str(canvas, 76, 10, SPIN_CHARS[app->spin & 3]);
    canvas_draw_str(canvas, 88, 10, app->status);
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 2,  22, "OP:");
    canvas_draw_str(canvas, 26, 22, app->cmd_disp);
    canvas_draw_str(canvas, 2,  34, "STEP:");
    canvas_draw_str(canvas, 34, 34, app->detail_disp);
    canvas_draw_str(canvas, 2,  46, "PROOF:");
    canvas_draw_str(canvas, 38, 46, app->rx_disp);
    canvas_draw_frame(canvas, 2, 52, 124, 6);
    uint8_t fill = (app->progress > 100) ? 124 : (uint8_t)((app->progress * 124) / 100);
    if(fill > 0) canvas_draw_box(canvas, 2, 52, fill, 6);
    canvas_draw_str(canvas, 2,  62, "[BACK] exit");
}
static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(ev->type == InputTypeShort && ev->key == InputKeyBack)
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop);
}

/* ═══════════════════════════════════════════════════════════════════
   JSON string unescape (in-place: \\n→\n, \\r→\r, \\t→\t, etc.)
   Required because jsmn returns raw JSON token bytes without unescaping.
   ═══════════════════════════════════════════════════════════════════ */
static void json_unescape(char* s) {
    char* r = s;
    char* w = s;
    while(*r) {
        if(*r == '\\' && *(r + 1)) {
            r++;
            switch(*r) {
                case 'n':  *w++ = '\n'; break;
                case 'r':  *w++ = '\r'; break;
                case 't':  *w++ = '\t'; break;
                case '"':  *w++ = '"';  break;
                case '\\': *w++ = '\\'; break;
                case '/':  *w++ = '/';  break;
                default:   *w++ = '\\'; *w++ = *r; break;
            }
        } else {
            *w++ = *r;
        }
        r++;
    }
    *w = '\0';
}

/* ═══════════════════════════════════════════════════════════════════
   jsmn helpers
   ═══════════════════════════════════════════════════════════════════ */
static bool tok_eq(const char* js, const jsmntok_t* t, const char* s) {
    size_t tlen = (size_t)(t->end - t->start);
    return (t->type == JSMN_STRING || t->type == JSMN_PRIMITIVE) &&
           strlen(s) == tlen && strncmp(js + t->start, s, tlen) == 0;
}
static bool json_str(const char* js, jsmntok_t* toks, int n,
                     const char* key, char* out, size_t out_sz) {
    for(int i = 1; i < n - 1; i += 2) {
        if(tok_eq(js, &toks[i], key)) {
            size_t vlen = (size_t)(toks[i+1].end - toks[i+1].start);
            if(vlen >= out_sz) vlen = out_sz - 1;
            memcpy(out, js + toks[i+1].start, vlen);
            out[vlen] = '\0';
            return true;
        }
    }
    return false;
}
static long long pen15_parse_ll(const char* s) {
    long long r = 0; int sign = 1;
    while(*s == ' ') s++;
    if(*s == '-') { sign = -1; s++; } else if(*s == '+') s++;
    while(*s >= '0' && *s <= '9') { r = r * 10 + (*s - '0'); s++; }
    return sign * r;
}
static int json_int(const char* js, jsmntok_t* toks, int n,
                    const char* key, int def) {
    char tmp[24] = {0};
    return json_str(js, toks, n, key, tmp, sizeof(tmp)) ? (int)pen15_parse_ll(tmp) : def;
}
static long long json_ll(const char* js, jsmntok_t* toks, int n,
                         const char* key, long long def) {
    char tmp[24] = {0};
    return json_str(js, toks, n, key, tmp, sizeof(tmp)) ? pen15_parse_ll(tmp) : def;
}

/* ═══════════════════════════════════════════════════════════════════
   Hardware stop-all
   ═══════════════════════════════════════════════════════════════════ */
static void hw_stop_all(Pen15App* app) {
    if((app->hw_state == HwRfidRead || app->hw_state == HwRfidEmulate) && app->rfid_worker) {
        lfrfid_worker_stop(app->rfid_worker);
        lfrfid_worker_free(app->rfid_worker);
        app->rfid_worker = NULL;
        if(app->rfid_dict) { protocol_dict_free(app->rfid_dict); app->rfid_dict = NULL; }
    }
    if(app->hw_state == HwIrRx && app->ir_worker) {
        infrared_worker_rx_stop(app->ir_worker);
        infrared_worker_free(app->ir_worker);
        app->ir_worker = NULL;
    }
    if((app->hw_state == HwIkeyRead || app->hw_state == HwIkeyEmulate) && app->ibutton_worker) {
        ibutton_worker_stop(app->ibutton_worker);
        ibutton_worker_free(app->ibutton_worker);
        app->ibutton_worker = NULL;
        if(app->ibutton_protocols) { ibutton_protocols_free(app->ibutton_protocols); app->ibutton_protocols = NULL; }
        /* ibutton_key is kept alive after read so ikey_emulate can reuse it */
    }
    if(app->hw_state == HwNfcDetect && app->nfc_scanner) {
        nfc_scanner_stop(app->nfc_scanner);
        nfc_scanner_free(app->nfc_scanner);
        app->nfc_scanner = NULL;
        if(app->nfc) { nfc_free(app->nfc); app->nfc = NULL; }
    }
    if((app->hw_state == HwSubghzRx || app->hw_state == HwSubghzRecord) && app->subghz_worker) {
        subghz_worker_stop(app->subghz_worker);
        subghz_worker_free(app->subghz_worker);
        app->subghz_worker = NULL;
        furi_hal_subghz_sleep();
    }
    if(app->hw_state == HwSubghzTx) {
        furi_hal_subghz_stop_async_tx();
        furi_hal_subghz_sleep();
    }
    app->hw_state = HwIdle;
}

/* ═══════════════════════════════════════════════════════════════════
   Storage helpers
   ═══════════════════════════════════════════════════════════════════ */
static bool storage_write_file(const char* path, const char* data, size_t len) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    File*    file    = storage_file_alloc(storage);
    bool     ok      = false;
    if(storage_file_open(file, path, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        ok = storage_file_write(file, data, len) == len;
        storage_file_close(file);
    }
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
    return ok;
}

static size_t storage_read_file(const char* path, char* buf, size_t buf_sz) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    File*    file    = storage_file_alloc(storage);
    size_t   got     = 0;
    if(storage_file_open(file, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        got = storage_file_read(file, buf, buf_sz - 1);
        buf[got] = '\0';
        storage_file_close(file);
    }
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
    return got;
}

/* ═══════════════════════════════════════════════════════════════════
   Hardware callbacks (called from worker threads / ISR)
   ═══════════════════════════════════════════════════════════════════ */

/* RFID */
static void rfid_cb(LFRFIDWorkerReadResult result, ProtocolId proto, void* ctx) {
    Pen15App* app = ctx;
    if(result == LFRFIDWorkerReadDone) {
        const char* name = protocol_dict_get_name(app->rfid_dict, proto);

        uint8_t data[32]; memset(data, 0, sizeof(data));
        size_t data_size = protocol_dict_get_data_size(app->rfid_dict, proto);
        if(data_size > sizeof(data)) data_size = sizeof(data);
        protocol_dict_get_data(app->rfid_dict, proto, data, data_size);

        char hex[65] = {0};
        for(size_t i = 0; i < data_size; i++)
            snprintf(hex + i * 2, 3, "%02X", data[i]);

        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"action\":\"rfid_read\","
            "\"message\":\"RFID credential captured\","
            "\"detail\":\"LF reader decoded a nearby tag\","
            "\"evidence\":\"%s %s\",\"type\":\"%s\",\"data\":\"%s\",\"progress\":100,\"id\":\"%s\"}\n",
            name ? name : "RFID", hex, name ? name : "RFID", hex, app->hw_id);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* Infrared RX */
static void ir_rx_cb(void* ctx, InfraredWorkerSignal* signal) {
    Pen15App* app = ctx;
    if(infrared_worker_signal_is_decoded(signal)) {
        const InfraredMessage* msg = infrared_worker_get_decoded_signal(signal);
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"action\":\"ir_rx\","
            "\"message\":\"Infrared signal decoded\","
            "\"detail\":\"Flipper IR receiver captured a valid frame\","
            "\"evidence\":\"%s 0x%lX/0x%lX\",\"progress\":100,"
            "\"protocol\":\"%s\",\"address\":%lu,\"command\":%lu,\"id\":\"%s\"}\n",
            infrared_get_protocol_name(msg->protocol),
            (unsigned long)msg->address,
            (unsigned long)msg->command,
            infrared_get_protocol_name(msg->protocol),
            (unsigned long)msg->address,
            (unsigned long)msg->command,
            app->hw_id);
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* iButton */
static void ibutton_cb(void* ctx) {
    Pen15App* app = ctx;
    iButtonProtocolId pid = ibutton_key_get_protocol_id(app->ibutton_key);
    const char* name = ibutton_protocols_get_name(app->ibutton_protocols, pid);

    FuriString* data_str = furi_string_alloc();
    ibutton_protocols_render_brief_data(app->ibutton_protocols, app->ibutton_key, data_str);

    snprintf(app->hw_result_json, sizeof(app->hw_result_json),
        "{\"status\":\"ok\",\"action\":\"ikey_read\","
        "\"message\":\"iButton credential captured\","
        "\"detail\":\"1-Wire reader decoded the touched key\","
        "\"evidence\":\"%s %s\",\"type\":\"%s\",\"data\":\"%s\",\"progress\":100,\"id\":\"%s\"}\n",
        name ? name : "iButton",
        furi_string_get_cstr(data_str),
        name ? name : "iButton",
        furi_string_get_cstr(data_str),
        app->hw_id);

    furi_string_free(data_str);
    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
}

/* NFC scanner */
static void nfc_scanner_cb(NfcScannerEvent event, void* ctx) {
    Pen15App* app = ctx;
    if(event.type == NfcScannerEventTypeDetected) {
        const char* proto_name = "NFC";
        if(event.data.protocol_num > 0)
            proto_name = nfc_device_get_protocol_name(event.data.protocols[0]);

        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"action\":\"nfc_detect\","
            "\"message\":\"NFC tag detected\","
            "\"detail\":\"Flipper field sensed a compatible NFC protocol\","
            "\"evidence\":\"%s field interaction\",\"type\":\"%s\",\"uid\":\"\","
            "\"progress\":100,\"id\":\"%s\"}\n",
            proto_name, proto_name, app->hw_id);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* SubGHz RX pair callback */
static void subghz_rx_pair_cb(void* ctx, bool level, uint32_t duration) {
    Pen15App* app = ctx;
    app->subghz_rx_count++;

    if(app->hw_state == HwSubghzRecord) {
        if(app->rx_timings_count < RX_MAX_TIMES) {
            app->rx_timings[app->rx_timings_count++] = level ? (int32_t)duration : -(int32_t)duration;
        }
        if(app->rx_timings_count >= RX_MAX_TIMES) {
            app->hw_state = HwIdle;
            /* Format timings as comma-separated signed ints */
            char* p = app->hw_result_json;
            int remaining = (int)sizeof(app->hw_result_json);
            int written = snprintf(p, (size_t)remaining,
                "{\"status\":\"ok\",\"action\":\"subghz_record\","
                "\"message\":\"SubGHz recording complete\","
                "\"detail\":\"Raw pulse timings captured from RF activity\","
                "\"evidence\":\"%zu timings recorded\",\"progress\":100,"
                "\"count\":%zu,\"timings\":\"",
                app->rx_timings_count,
                app->rx_timings_count);
            p += written; remaining -= written;
            for(size_t i = 0; i < app->rx_timings_count && remaining > 16; i++) {
                written = snprintf(p, (size_t)remaining, i == 0 ? "%ld" : ",%ld",
                    (long)app->rx_timings[i]);
                p += written; remaining -= written;
            }
            written = snprintf(p, (size_t)remaining, "\",\"id\":\"%s\"}\n", app->hw_id);
            furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
        }
        return;
    }

    if(app->subghz_rx_count >= 50 && app->hw_state == HwSubghzRx) {
        app->hw_state = HwIdle;
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"action\":\"subghz_rx\","
            "\"message\":\"SubGHz activity detected\","
            "\"detail\":\"Receiver observed repeated OOK transitions\","
            "\"evidence\":\"%u pulses counted\",\"progress\":100,"
            "\"count\":%u,\"timings\":\"\",\"id\":\"%s\"}\n",
            (unsigned)app->subghz_rx_count, (unsigned)app->subghz_rx_count, app->hw_id);
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* SubGHz TX ISR callback — ISR context, must be fast */
static LevelDuration subghz_tx_isr(void* ctx) {
    Pen15App* app = ctx;
    if(app->tx_idx >= app->tx_count) {
        app->tx_repeat_cnt++;
        if(app->tx_repeat_cnt >= app->tx_repeat) {
            furi_thread_flags_set(furi_thread_get_id(app->thread), EvtTxDone);
            return level_duration_reset();
        }
        app->tx_idx = 0;
    }
    int32_t v = app->tx_timings[app->tx_idx++];
    return level_duration_make(v > 0, (uint32_t)(v > 0 ? v : -v));
}

/* ═══════════════════════════════════════════════════════════════════
   Parse comma-separated timing string into int32 array.
   "+350,-10850,350,-1050,..." → {350,-10850,350,-1050,...}
   ═══════════════════════════════════════════════════════════════════ */
static size_t parse_timings(const char* str, int32_t* out, size_t max) {
    size_t count = 0;
    const char* p = str;
    while(*p && count < max) {
        while(*p == ' ') p++;
        if(*p == '\0') break;
        out[count++] = (int32_t)strtol(p, (char**)&p, 10);
        while(*p == ',' || *p == ' ') p++;
    }
    return count;
}

/* ═══════════════════════════════════════════════════════════════════
   JSON command dispatch
   ═══════════════════════════════════════════════════════════════════ */
static void handle_json(Pen15App* app, const char* js, size_t len) {
    jsmn_parser parser;
    jsmntok_t   toks[MAX_TOKENS];
    static char resp[768];
    char action[32] = {0};
    char id[16]     = {0};

    jsmn_init(&parser);
    int n = jsmn_parse(&parser, js, len, toks, MAX_TOKENS);
    if(n < 1 || toks[0].type != JSMN_OBJECT) {
        usb_send(app, "{\"status\":\"error\",\"code\":\"PARSE_ERR\"}\n");
        return;
    }

    json_str(js, toks, n, "action", action, sizeof(action));
    json_str(js, toks, n, "id",     id,     sizeof(id));

    app->spin++;
    ui_state(app, "RX OK", action, "json parsed", "USB CDC frame ok", 8);
    emit_status(
        app,
        id,
        action,
        "received",
        "Command received",
        "JSON parsed on Flipper",
        "USB CDC frame ok",
        8);

    /* ── ping ──────────────────────────────────────────────────────── */
    if(strcmp(action, "ping") == 0) {
        ui_state(app, "RUN", "ping", "handshake reply", "USB CDC alive", 40);
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"ping\",\"message\":\"Handshake complete\","
            "\"detail\":\"Pen15 Controller responding\",\"evidence\":\"USB CDC alive\","
            "\"progress\":100,\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap\":\"2.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "CONN", "ping", "reply sent", "phone ack ready", 100);

    /* ── gpio_mode ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_mode") == 0) {
        int  pin  = json_int(js, toks, n, "pin", -1);
        char mode[16] = {0};
        json_str(js, toks, n, "mode", mode, sizeof(mode));
        ui_state(app, "GPIO", action, "configuring pin", mode, 45);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"gpio_mode\",\"code\":\"BAD_PIN\","
                "\"message\":\"Invalid GPIO index\",\"detail\":\"Expected pin 0-7\","
                "\"evidence\":\"pin out of range\",\"progress\":100,\"id\":\"%s\"}\n", id);
        } else {
            if(strcmp(mode, "output") == 0) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeOutputPushPull, GpioPullNo, GpioSpeedMedium);
                app->pin_mode[pin] = PinOutput;
            } else {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput, GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"gpio_mode\",\"message\":\"GPIO mode set\","
                "\"detail\":\"Pin configured on Flipper header\",\"evidence\":\"pin %d -> %s\","
                "\"progress\":100,\"pin\":%d,\"mode\":\"%s\",\"id\":\"%s\"}\n",
                pin, mode, pin, mode, id);
        }
        usb_send(app, resp);
        ui_state(app, "GPIO", action, "pin configured", mode, 100);

    /* ── gpio_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_write") == 0) {
        int pin   = json_int(js, toks, n, "pin",   -1);
        int value = json_int(js, toks, n, "value", -1);
        ui_state(app, "GPIO", action, "driving output", value ? "HIGH" : "LOW", 55);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"gpio_write\",\"code\":\"BAD_PIN\","
                "\"message\":\"Invalid GPIO index\",\"detail\":\"Expected pin 0-7\","
                "\"evidence\":\"pin out of range\",\"progress\":100,\"id\":\"%s\"}\n", id);
        } else if(app->pin_mode[pin] != PinOutput) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"gpio_write\",\"code\":\"NOT_OUTPUT\","
                "\"message\":\"Pin is not configured for output\","
                "\"detail\":\"Call gpio_mode output first\","
                "\"evidence\":\"write blocked for safety\",\"progress\":100,\"id\":\"%s\"}\n", id);
        } else {
            furi_hal_gpio_write(EXT_PINS[pin], value != 0);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"gpio_write\",\"message\":\"GPIO level driven\","
                "\"detail\":\"Output changed on Flipper header\","
                "\"evidence\":\"pin %d = %d\",\"progress\":100,"
                "\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, value != 0 ? 1 : 0, pin, value != 0 ? 1 : 0, id);
        }
        usb_send(app, resp);
        ui_state(app, "GPIO", action, "write complete", value ? "HIGH" : "LOW", 100);

    /* ── gpio_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_read") == 0) {
        int pin = json_int(js, toks, n, "pin", -1);
        ui_state(app, "GPIO", action, "sampling input", "waiting for level", 45);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"gpio_read\",\"code\":\"BAD_PIN\","
                "\"message\":\"Invalid GPIO index\",\"detail\":\"Expected pin 0-7\","
                "\"evidence\":\"pin out of range\",\"progress\":100,\"id\":\"%s\"}\n", id);
        } else {
            if(app->pin_mode[pin] == PinUnset) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput, GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            bool val = furi_hal_gpio_read(EXT_PINS[pin]);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"gpio_read\",\"message\":\"GPIO sampled\","
                "\"detail\":\"Input level read from Flipper header\","
                "\"evidence\":\"pin %d = %d\",\"progress\":100,"
                "\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, val ? 1 : 0, pin, val ? 1 : 0, id);
            ui_state(app, "GPIO", action, "sample complete", val ? "HIGH" : "LOW", 100);
        }
        usb_send(app, resp);

    /* ── uart_init ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_init") == 0) {
        int baud = json_int(js, toks, n, "baud", 115200);
        bool uart_ok = false;
        ui_state(app, "UART", action, "arming usart1", "pins 13/14", 35);
        if(!app->uart_ready) {
            app->serial = furi_hal_serial_control_acquire(FuriHalSerialIdUsart);
            if(app->serial) {
                furi_hal_serial_init(app->serial, (uint32_t)baud);
                furi_hal_serial_dma_rx_start(app->serial, uart_rx_dma_cb, app, false);
                app->uart_ready = true;
                uart_ok = true;
                snprintf(resp, sizeof(resp),
                    "{\"status\":\"ok\",\"action\":\"uart_init\",\"message\":\"UART armed\","
                    "\"detail\":\"USART1 ready for AWOK traffic\","
                    "\"evidence\":\"pins 13/14 @ %d\",\"progress\":100,"
                    "\"baud\":%d,\"id\":\"%s\"}\n", baud, baud, id);
            } else {
                snprintf(resp, sizeof(resp),
                    "{\"status\":\"error\",\"action\":\"uart_init\",\"code\":\"UART_BUSY\","
                    "\"message\":\"UART peripheral is busy\","
                    "\"detail\":\"USART1 control acquisition failed\","
                    "\"evidence\":\"another task owns UART\",\"progress\":100,\"id\":\"%s\"}\n", id);
            }
        } else {
            furi_hal_serial_set_br(app->serial, (uint32_t)baud);
            uart_ok = true;
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"uart_init\",\"message\":\"UART speed updated\","
                "\"detail\":\"USART1 already active and reconfigured\","
                "\"evidence\":\"pins 13/14 @ %d\",\"progress\":100,"
                "\"baud\":%d,\"id\":\"%s\"}\n", baud, baud, id);
        }
        usb_send(app, resp);
        if(uart_ok) ui_state(app, "UART", action, "ready", "awaiting bridge open", 100);

    /* ── bridge_open ───────────────────────────────────────────────── */
    } else if(strcmp(action, "bridge_open") == 0) {
        if(!app->uart_ready) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"bridge_open\",\"code\":\"UART_NOT_INIT\","
                "\"message\":\"UART not ready for bridge mode\","
                "\"detail\":\"Run uart_init before opening the bridge\","
                "\"evidence\":\"JSON mode retained\",\"progress\":100,\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            ui_state(app, "ERR", action, "bridge blocked", "uart init required", 100);
        } else {
            app->bridge_mode = true;
            app->app_mode = ModeBridge;
            app->bridge_exit_tick = 0;
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"bridge_open\",\"message\":\"USB/UART bridge live\","
                "\"detail\":\"Raw AWOK traffic now streams through Flipper\","
                "\"evidence\":\"DTR low exits bridge\",\"progress\":100,\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            ui_state(app, "BRIDGE", action, "usb <-> awok live", "DTR low exits", 100);
        }

    /* ── uart_send ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_send") == 0) {
        static char data[128]; memset(data, 0, sizeof(data));
        json_str(js, toks, n, "data", data, sizeof(data));
        ui_state(app, "UART", action, "forwarding bytes", data, 45);
        if(!app->uart_ready) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"action\":\"uart_send\",\"code\":\"UART_NOT_INIT\","
                "\"message\":\"UART not initialized\",\"detail\":\"Run uart_init first\","
                "\"evidence\":\"AWOK bytes not sent\",\"progress\":100,\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
        } else {
            furi_stream_buffer_reset(app->uart_rx_buf);
            furi_hal_serial_tx(app->serial, (uint8_t*)data, strlen(data));
            furi_hal_serial_tx_wait_complete(app->serial);
            static char awok[200]; memset(awok, 0, sizeof(awok));
            size_t awok_len = 0;
            uint32_t deadline = furi_get_tick() + furi_ms_to_ticks(UART_RX_WAIT);
            while(furi_get_tick() < deadline && awok_len < sizeof(awok) - 1) {
                uint8_t b; size_t got = furi_stream_buffer_receive(app->uart_rx_buf, &b, 1, 0);
                if(got > 0) awok[awok_len++] = (char)b;
                else furi_delay_ms(5);
            }
            for(size_t i = 0; i < awok_len; i++) {
                if(awok[i] == '"')  awok[i] = '\'';
                if(awok[i] == '\r') awok[i] = ' ';
                if(awok[i] == '\n') awok[i] = '|';
                if((unsigned char)awok[i] < 32) awok[i] = ' ';
            }
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"action\":\"uart_send\",\"message\":\"UART exchange complete\","
                "\"detail\":\"Bytes forwarded across Flipper GPIO bridge\","
                "\"evidence\":\"%s\",\"progress\":100,\"uart_rx\":\"%s\",\"id\":\"%s\"}\n",
                awok_len > 0 ? awok : "(no rx)", awok, id);
            usb_send(app, resp);
            ui_state(app, "UART", action, "exchange complete", awok_len > 0 ? awok : "(no rx)", 100);
        }

    /* ── get_device_info ───────────────────────────────────────────── */
    } else if(strcmp(action, "get_device_info") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"get_device_info\","
            "\"message\":\"Device inventory ready\",\"detail\":\"Reporting Flipper + FAP build\","
            "\"evidence\":\"controller online\",\"progress\":100,\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap_ver\":\"2.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "INFO", action, "inventory sent", "controller online", 100);

    /* ── hw_stop ───────────────────────────────────────────────────── */
    } else if(strcmp(action, "hw_stop") == 0) {
        app->bridge_mode = false;
        app->app_mode = ModeJson;
        hw_stop_all(app);
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"hw_stop\",\"message\":\"Hardware activity stopped\","
            "\"detail\":\"Bridge and active workers returned to idle\","
            "\"evidence\":\"safe idle state\",\"progress\":100,\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "IDLE", action, "all activity stopped", "safe idle state", 100);

    /* ── rfid_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "rfid_read") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->rfid_dict   = protocol_dict_alloc(lfrfid_protocols, LFRFIDProtocolMax);
        app->rfid_worker = lfrfid_worker_alloc(app->rfid_dict);
        lfrfid_worker_read_start(app->rfid_worker, LFRFIDWorkerReadTypeAuto, rfid_cb, app);
        app->hw_state = HwRfidRead;

        emit_status(app, id, action, "reading", "RFID reader armed", "Waiting for LF tag energy", "reader active", 25);
        ui_state(app, "RFID", action, "reader armed", "present LF tag", 25);

    /* ── nfc_detect ────────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_detect") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->nfc         = nfc_alloc();
        app->nfc_scanner = nfc_scanner_alloc(app->nfc);
        nfc_scanner_start(app->nfc_scanner, nfc_scanner_cb, app);
        app->hw_state = HwNfcDetect;

        emit_status(app, id, action, "scanning", "NFC field active", "Scanning for nearby tag", "field energized", 25);
        ui_state(app, "NFC", action, "field active", "tap tag now", 25);

    /* ── ir_rx ─────────────────────────────────────────────────────── */
    } else if(strcmp(action, "ir_rx") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->ir_worker = infrared_worker_alloc();
        infrared_worker_rx_enable_signal_decoding(app->ir_worker, true);
        infrared_worker_rx_set_received_signal_callback(app->ir_worker, ir_rx_cb, app);
        infrared_worker_rx_start(app->ir_worker);
        app->hw_state = HwIrRx;

        emit_status(app, id, action, "reading", "IR learner armed", "Waiting for remote transmission", "sensor listening", 25);
        ui_state(app, "IR", action, "sensor listening", "point remote now", 25);

    /* ── ir_tx ─────────────────────────────────────────────────────── */
    } else if(strcmp(action, "ir_tx") == 0) {
        char proto_name[32] = {0};
        json_str(js, toks, n, "protocol", proto_name, sizeof(proto_name));
        long long address = json_ll(js, toks, n, "address", 0);
        long long command = json_ll(js, toks, n, "command", 0);
        ui_state(app, "IR", action, "transmitting", proto_name, 55);

        InfraredProtocol proto = infrared_get_protocol_by_name(proto_name);
        if(proto == InfraredProtocolUnknown) proto = InfraredProtocolNEC;

        InfraredMessage msg = {
            .protocol = proto,
            .address  = (uint32_t)address,
            .command  = (uint32_t)command,
            .repeat   = false,
        };

        InfraredWorker* tx_worker = infrared_worker_alloc();
        infrared_worker_set_decoded_signal(tx_worker, &msg);
        infrared_worker_tx_set_get_signal_callback(
            tx_worker, infrared_worker_tx_get_signal_steady_callback, tx_worker);
        infrared_worker_tx_start(tx_worker);
        furi_delay_ms(200);
        infrared_worker_tx_stop(tx_worker);
        infrared_worker_free(tx_worker);

        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"ir_tx\",\"message\":\"IR burst transmitted\","
            "\"detail\":\"Flipper IR LED completed requested send\","
            "\"evidence\":\"protocol %s\",\"progress\":100,\"id\":\"%s\"}\n", proto_name, id);
        usb_send(app, resp);
        ui_state(app, "IR", action, "transmit complete", proto_name, 100);

    /* ── ikey_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "ikey_read") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        if(app->ibutton_key) { ibutton_key_free(app->ibutton_key); app->ibutton_key = NULL; }
        app->ibutton_protocols = ibutton_protocols_alloc();
        app->ibutton_key       = ibutton_key_alloc(64);
        app->ibutton_worker    = ibutton_worker_alloc(app->ibutton_protocols);
        ibutton_worker_read_set_callback(app->ibutton_worker, ibutton_cb, app);
        ibutton_worker_read_start(app->ibutton_worker, app->ibutton_key);
        app->hw_state = HwIkeyRead;

        emit_status(app, id, action, "reading", "iButton reader armed", "Waiting for key contact", "1-Wire active", 25);
        ui_state(app, "KEY", action, "reader armed", "touch key now", 25);

    /* ── subghz_rx ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_rx") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick  = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);
        app->subghz_rx_count   = 0;

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_rx();

        app->subghz_worker = subghz_worker_alloc();
        subghz_worker_set_pair_callback(app->subghz_worker, subghz_rx_pair_cb);
        subghz_worker_set_context(app->subghz_worker, app);
        subghz_worker_start(app->subghz_worker);
        app->hw_state = HwSubghzRx;

        emit_status(app, id, action, "scanning", "SubGHz receiver armed", "Listening for OOK activity", "CC1101 in RX", 25);
        ui_state(app, "RF RX", action, "receiver armed", "listening for RF", 25);

    /* ── subghz_record ────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_record") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick  = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);
        app->subghz_rx_count   = 0;
        app->rx_timings_count  = 0;
        app->subghz_record_mode = true;

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_rx();

        app->subghz_worker = subghz_worker_alloc();
        subghz_worker_set_pair_callback(app->subghz_worker, subghz_rx_pair_cb);
        subghz_worker_set_context(app->subghz_worker, app);
        subghz_worker_start(app->subghz_worker);
        app->hw_state = HwSubghzRecord;

        emit_status(app, id, action, "recording", "SubGHz recorder armed", "Capturing raw pulse timings", "CC1101 recording", 25);
        ui_state(app, "RF REC", action, "recording pulses", "waiting for RF", 25);

    /* ── subghz_tx_raw ─────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_tx_raw") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        int repeat     = json_int(js, toks, n, "repeat", 3);

        static char timings_str[1024]; memset(timings_str, 0, sizeof(timings_str));
        json_str(js, toks, n, "timings", timings_str, sizeof(timings_str));

        app->tx_count      = parse_timings(timings_str, app->tx_timings, TX_MAX_TIMES);
        app->tx_idx        = 0;
        app->tx_repeat     = repeat;
        app->tx_repeat_cnt = 0;
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);

        if(app->tx_count == 0) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_TIMINGS\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_start_async_tx(subghz_tx_isr, app);
        app->hw_state = HwSubghzTx;

        emit_status(app, id, action, "running", "SubGHz transmitter active", "Broadcasting requested raw timings", "carrier active", 30);
        ui_state(app, "RF TX", action, "broadcasting", "carrier active", 30);

    /* ── storage_write ─────────────────────────────────────────────── */
    } else if(strcmp(action, "storage_write") == 0) {
        static char path[128];    memset(path, 0, sizeof(path));
        static char content[1024]; memset(content, 0, sizeof(content));
        json_str(js, toks, n, "path",    path,    sizeof(path));
        json_str(js, toks, n, "content", content, sizeof(content));
        json_unescape(content);

        bool ok = storage_write_file(path, content, strlen(content));
        snprintf(resp, sizeof(resp),
            "{\"status\":\"%s\",\"action\":\"storage_write\","
            "\"message\":\"%s\",\"detail\":\"Flipper storage operation finished\","
            "\"evidence\":\"%s\",\"progress\":100,\"id\":\"%s\"}\n",
            ok ? "ok" : "error",
            ok ? "File written" : "File write failed",
            ok ? "storage write ok" : "storage write err",
            id);
        usb_send(app, resp);
        ui_state(app, ok ? "FILE" : "ERR", action, ok ? "write complete" : "write failed", ok ? "storage write ok" : "storage write err", 100);

    /* ── storage_read ──────────────────────────────────────────────── */
    } else if(strcmp(action, "storage_read") == 0) {
        static char path[128]; memset(path, 0, sizeof(path));
        json_str(js, toks, n, "path", path, sizeof(path));

        static char content[512]; memset(content, 0, sizeof(content));
        size_t got = storage_read_file(path, content, sizeof(content));

        /* Escape double quotes for JSON */
        static char escaped[512]; size_t elen = 0;
        for(size_t i = 0; i < got && elen < sizeof(escaped) - 2; i++) {
            if(content[i] == '"') { escaped[elen++] = '\\'; }
            escaped[elen++] = content[i];
        }
        escaped[elen] = '\0';

        snprintf(resp, sizeof(resp),
            "{\"status\":\"%s\",\"action\":\"storage_read\","
            "\"message\":\"%s\",\"detail\":\"Flipper storage read finished\","
            "\"evidence\":\"%s\",\"progress\":100,\"content\":\"%s\",\"id\":\"%s\"}\n",
            got > 0 ? "ok" : "error",
            got > 0 ? "File read complete" : "File read failed",
            got > 0 ? "storage read ok" : "storage read err",
            escaped, id);
        usb_send(app, resp);
        ui_state(app, got > 0 ? "FILE" : "ERR", action, got > 0 ? "read complete" : "read failed", got > 0 ? "storage read ok" : "storage read err", 100);

    /* ── rfid_emulate ─────────────────────────────────────────────── */
    } else if(strcmp(action, "rfid_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        char type_str[32] = {0};
        char data_hex[65] = {0};
        json_str(js, toks, n, "type", type_str, sizeof(type_str));
        json_str(js, toks, n, "data", data_hex, sizeof(data_hex));

        app->rfid_dict   = protocol_dict_alloc(lfrfid_protocols, LFRFIDProtocolMax);
        app->rfid_worker = lfrfid_worker_alloc(app->rfid_dict);

        ProtocolId proto = PROTOCOL_NO;
        for(ProtocolId p = 0; p < LFRFIDProtocolMax; p++) {
            if(strcmp(protocol_dict_get_name(app->rfid_dict, p), type_str) == 0) {
                proto = p; break;
            }
        }
        if(proto == PROTOCOL_NO) proto = 0;

        size_t data_sz = protocol_dict_get_data_size(app->rfid_dict, proto);
        uint8_t raw[32]; memset(raw, 0, sizeof(raw));
        size_t hex_len = strlen(data_hex);
        for(size_t i = 0; i + 1 < hex_len && i/2 < sizeof(raw); i += 2) {
            char byte_str[3] = { data_hex[i], data_hex[i+1], '\0' };
            raw[i/2] = (uint8_t)strtol(byte_str, NULL, 16);
        }
        protocol_dict_set_data(app->rfid_dict, proto, raw, data_sz);

        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        lfrfid_worker_emulate_start(app->rfid_worker, proto);
        app->hw_state = HwRfidEmulate;

        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"rfid_emulate\",\"message\":\"RFID emulation active\","
            "\"detail\":\"Flipper LF hardware is replaying the supplied credential\","
            "\"evidence\":\"credential loaded\",\"progress\":100,\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "RFID EM", action, "credential loaded", "emulation active", 100);

    /* ── ikey_emulate ─────────────────────────────────────────────── */
    } else if(strcmp(action, "ikey_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        if(!app->ibutton_key) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_KEY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        app->ibutton_protocols = ibutton_protocols_alloc();
        app->ibutton_worker    = ibutton_worker_alloc(app->ibutton_protocols);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        ibutton_worker_emulate_start(app->ibutton_worker, app->ibutton_key);
        app->hw_state = HwIkeyEmulate;

        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"ikey_emulate\",\"message\":\"iButton emulation active\","
            "\"detail\":\"Stored iButton credential is being replayed\","
            "\"evidence\":\"1-Wire emulation active\",\"progress\":100,\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "KEY EM", action, "credential loaded", "1-wire active", 100);

    /* ── nfc_emulate ──────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_emulate") == 0) {
        char uid_hex[32] = {0};
        char type_str[32] = {0};
        json_str(js, toks, n, "uid",  uid_hex,  sizeof(uid_hex));
        json_str(js, toks, n, "type", type_str, sizeof(type_str));
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"action\":\"nfc_emulate\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_emulate requires NFC app\","
            "\"detail\":\"Current controller does not own Flipper NFC emulation stack\","
            "\"evidence\":\"use dedicated NFC app\",\"progress\":100,\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "ERR", action, "not supported", "use NFC app", 100);

    /* ── nfc_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_write") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"action\":\"nfc_write\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_write requires NFC app\","
            "\"detail\":\"Controller cannot write NFC tags directly\","
            "\"evidence\":\"use dedicated NFC app\",\"progress\":100,\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        ui_state(app, "ERR", action, "not supported", "use NFC app", 100);

    /* ── unknown ───────────────────────────────────────────────────── */
    } else {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"action\":\"%s\",\"code\":\"UNKNOWN\","
            "\"message\":\"Unknown command\","
            "\"detail\":\"Controller does not implement this action\","
            "\"evidence\":\"%s\",\"progress\":100,\"id\":\"%s\"}\n", action, action, id);
        usb_send(app, resp);
        ui_state(app, "ERR", action, "unknown command", action, 100);
    }

    view_port_update(app->vp);
}

/* ── Process incoming USB bytes ─────────────────────────────────── */
static void process_usb_rx(Pen15App* app) {
    uint8_t buf[USB_PKT_LEN];
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    int32_t got = furi_hal_cdc_receive(0, buf, USB_PKT_LEN);
    furi_mutex_release(app->usb_mtx);

    for(int32_t i = 0; i < got; i++) {
        char c = (char)buf[i];
        if(c == '\n' || c == '\r') {
            if(app->json_len > 0) {
                app->json_buf[app->json_len] = '\0';
                if(app->app_mode == ModeJson) {
                    handle_json(app, app->json_buf, app->json_len);
                }
                app->json_len = 0;
            }
        } else if(app->json_len < JSON_BUF_SZ - 1) {
            app->json_buf[app->json_len++] = c;
        }
    }
}

/* ── FAP entry point ────────────────────────────────────────────── */
int32_t pen15_app(void* p) {
    UNUSED(p);

    Pen15App* app = malloc(sizeof(Pen15App));
    memset(app, 0, sizeof(Pen15App));

    app->app_mode = ModeJson;
    app->menu_index = 0;
    app->init_done = true;

    app->thread      = furi_thread_get_current();
    app->usb_mtx     = furi_mutex_alloc(FuriMutexTypeNormal);
    app->tx_sem      = furi_semaphore_alloc(1, 1);
    app->uart_rx_buf = furi_stream_buffer_alloc(UART_RX_BUF, 1);
    app->hw_state    = HwIdle;

    strncpy(app->status,   "WAIT", sizeof(app->status)   - 1);
    strncpy(app->cmd_disp, "---",  sizeof(app->cmd_disp) - 1);
    strncpy(app->detail_disp, "launch app", sizeof(app->detail_disp) - 1);
    strncpy(app->rx_disp,  "---",  sizeof(app->rx_disp)  - 1);

    app->vp  = view_port_alloc();
    app->gui = furi_record_open(RECORD_GUI);
    view_port_draw_callback_set(app->vp,  draw_cb,  app);
    view_port_input_callback_set(app->vp, input_cb, app);
    gui_add_view_port(app->gui, app->vp, GuiLayerFullscreen);

    app->cli_vcp = furi_record_open(RECORD_CLI_VCP);
    cli_vcp_disable(app->cli_vcp);
    furi_hal_cdc_set_callbacks(0, (CdcCallbacks*)&CDC_CB, app);

    /* Main event loop */
    while(true) {
        uint32_t evts = furi_thread_flags_wait(ALL_EVENTS, FuriFlagWaitAny, 250);

        if(evts & FuriFlagError) {
            /* Timeout tick — check hw deadline */
            if(app->hw_state != HwIdle &&
               furi_get_tick() > app->hw_deadline_tick) {
                static char tout[256];
                snprintf(tout, sizeof(tout),
                    "{\"status\":\"error\",\"action\":\"%s\",\"code\":\"TIMEOUT\","
                    "\"message\":\"Hardware activity timed out\","
                    "\"detail\":\"No device-side completion before deadline\","
                    "\"evidence\":\"hardware watchdog expired\",\"progress\":100,\"id\":\"%s\"}\n",
                    app->cmd_disp,
                    app->hw_id);
                hw_stop_all(app);
                usb_send(app, tout);
                ui_state(app, "TIMEOUT", app->cmd_disp, "hardware watchdog", "no completion", 100);
            }
            app->spin++;
            view_port_update(app->vp);
            continue;
        }

        if(evts & EvtStop) break;

        if(evts & EvtUsbRx) {
            if(app->bridge_mode) {
                uint8_t usb_buf[USB_PKT_LEN];
                furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
                int32_t got = furi_hal_cdc_receive(0, usb_buf, USB_PKT_LEN);
                furi_mutex_release(app->usb_mtx);
                if(got > 0 && app->uart_ready)
                    furi_hal_serial_tx(app->serial, usb_buf, (size_t)got);
            } else {
                process_usb_rx(app);
            }
        }

        if(evts & EvtUartRx) {
            if(app->bridge_mode && app->uart_ready) {
                uint8_t uart_buf[256];
                size_t got;
                do {
                    got = furi_stream_buffer_receive(app->uart_rx_buf, uart_buf, sizeof(uart_buf), 0);
                    if(got > 0) usb_send_raw(app, uart_buf, (uint16_t)got);
                } while(got > 0);
            }
        }

        if(evts & EvtBridgeExit) {
            ui_state(app, "WAIT", "bridge_open", "bridge closed", "DTR low detected", 0);
        }

        if(evts & EvtHwDone) {
            /* Hardware read completed — result already in hw_result_json */
            hw_stop_all(app);
            usb_send(app, app->hw_result_json);
            ui_state(app, "DONE", app->cmd_disp, "result sent", "device finished", 100);
        }

        if(evts & EvtTxDone) {
            /* Async TX finished */
            furi_hal_subghz_stop_async_tx();
            furi_hal_subghz_sleep();
            app->hw_state = HwIdle;
            static char txresp[256];
            snprintf(txresp, sizeof(txresp),
                "{\"status\":\"ok\",\"action\":\"subghz_tx_raw\","
                "\"message\":\"SubGHz transmit complete\","
                "\"detail\":\"Requested raw timing sequence finished\","
                "\"evidence\":\"carrier stopped\",\"progress\":100,\"id\":\"%s\"}\n", app->hw_id);
            usb_send(app, txresp);
            ui_state(app, "DONE", "subghz_tx_raw", "carrier stopped", "transmit complete", 100);
        }

        app->spin++;
        view_port_update(app->vp);
    }

    /* ── Cleanup ──────────────────────────────────────────────────── */
    hw_stop_all(app);
    if(app->ibutton_key) { ibutton_key_free(app->ibutton_key); app->ibutton_key = NULL; }

    furi_hal_cdc_set_callbacks(0, NULL, NULL);
    cli_vcp_enable(app->cli_vcp);
    furi_record_close(RECORD_CLI_VCP);

    if(app->uart_ready && app->serial) {
        furi_hal_serial_deinit(app->serial);
        furi_hal_serial_control_release(app->serial);
    }

    for(int i = 0; i < 8; i++) {
        if(app->pin_mode[i] != PinUnset)
            furi_hal_gpio_init(EXT_PINS[i], GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    }

    gui_remove_view_port(app->gui, app->vp);
    view_port_free(app->vp);
    furi_record_close(RECORD_GUI);

    furi_stream_buffer_free(app->uart_rx_buf);
    furi_semaphore_free(app->tx_sem);
    furi_mutex_free(app->usb_mtx);
    free(app);

    return 0;
}
