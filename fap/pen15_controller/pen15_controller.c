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
#include <nfc/nfc_poller.h>
#include <nfc/nfc_listener.h>
#include <nfc/protocols/iso14443_3a/iso14443_3a.h>
#include <nfc/protocols/iso14443_3a/iso14443_3a_poller.h>
#include <nfc/protocols/iso14443_3a/iso14443_3a_listener.h>
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
    EvtNfcPhase2  = (1 << 6),
} Pen15Evt;
#define ALL_EVENTS (EvtStop | EvtUsbRx | EvtUartRx | EvtHwDone | EvtTxDone | EvtBridgeExit | EvtNfcPhase2)

/* ── Hardware state ───────────────────────────────────────────────── */
typedef enum {
    HwIdle,
    HwRfidRead,
    HwRfidEmulate,
    HwNfcDetect,
    HwNfcRead,
    HwNfcEmulate,
    HwIrRx,
    HwIkeyRead,
    HwIkeyEmulate,
    HwSubghzRx,
    HwSubghzRecord,
    HwSubghzTx,
} HwState;

typedef enum {
    ModeMenu,     /* Interactive menu */
    ModeBridge,  /* UART bridge to AWOK */
    ModeJson,    /* JSON protocol (default) */
} AppMode;

/* Menu strings for ModeMenu */
enum { MENU_COUNT = 14 };
static const char* SPIN_CHARS[4] = {"|", "/", "-", "\\"};
static const char* MENU_TITLES[MENU_COUNT] = {
    "RFID Read",
    "RFID Emulate",
    "NFC Detect",
    "NFC Emulate",
    "SubGHz RX",
    "SubGHz Record",
    "SubGHz TX",
    "IR Learn",
    "IR Transmit",
    "iButton Read",
    "iButton Emulate",
    "GPIO Control",
    "UART Bridge",
    "Exit",
};

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
    AppMode         app_mode;
    uint8_t         menu_index;
    uint32_t        bridge_exit_tick;
    bool            init_done;

    char   json_buf[JSON_BUF_SZ];
    size_t json_len;

    char   status[DISP_STR_LEN];
    char   cmd_disp[DISP_STR_LEN];
    char   rx_disp[DISP_STR_LEN];
    uint8_t progress;
    uint8_t spin;

    PinMode pin_mode[8];

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
    Nfc*         nfc;
    NfcScanner*  nfc_scanner;
    NfcPoller*   nfc_poller;
    NfcListener* nfc_listener;
    NfcProtocol  nfc_detected_proto;

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
        if(app->app_mode == ModeBridge && app->bridge_exit_tick &&
           furi_get_tick() > app->bridge_exit_tick) {
            app->bridge_mode = false; app->app_mode = ModeJson;
            furi_thread_flags_set(furi_thread_get_id(app->thread), EvtBridgeExit);
        }
    if(app->bridge_mode && !(s & 0x01)) {
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

/* ═══════════════════════════════════════════════════════════════════
   GUI
   ═══════════════════════════════════════════════════════════════════ */
/* ── Icon glyphs (5×5 pixel bitmaps drawn as 5 horizontal lines) ── */
static void draw_icon_rfid(Canvas* c, uint8_t x, uint8_t y) {
    /* RF waves: ))) */
    canvas_draw_str(c, x, y, ")))");
}
static void draw_icon_nfc(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "[N]");
}
static void draw_icon_sub(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "~Hz");
}
static void draw_icon_ir(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "IR*");
}
static void draw_icon_ikey(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "[K]");
}
static void draw_icon_gpio(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "I/O");
}
static void draw_icon_bridge(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "<=>");
}
static void draw_icon_exit(Canvas* c, uint8_t x, uint8_t y) {
    canvas_draw_str(c, x, y, "[ ]");
}

/* Map menu index to short icon string */
static void draw_menu_icon(Canvas* c, uint8_t idx, uint8_t x, uint8_t y) {
    switch(idx) {
        case 0: case 1: draw_icon_rfid(c, x, y);   break;
        case 2: case 3: draw_icon_nfc(c, x, y);    break;
        case 4: case 5: case 6: draw_icon_sub(c, x, y); break;
        case 7: case 8: draw_icon_ir(c, x, y);     break;
        case 9: case 10: draw_icon_ikey(c, x, y);  break;
        case 11: draw_icon_gpio(c, x, y);           break;
        case 12: draw_icon_bridge(c, x, y);         break;
        default: draw_icon_exit(c, x, y);           break;
    }
}

static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);

    /* ── Header — full-width inverted bar ────────────────────────── */
    canvas_set_color(canvas, ColorBlack);
    canvas_draw_box(canvas, 0, 0, 128, 12);
    canvas_set_color(canvas, ColorWhite);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "PEN15");

    /* Animated scan dots in header */
    uint8_t dot_phase = app->spin & 3;
    for(uint8_t d = 0; d < 4; d++) {
        uint8_t dx = 50 + d * 6;
        if(d < dot_phase)
            canvas_draw_box(canvas, dx, 4, 4, 4);
        else
            canvas_draw_frame(canvas, dx, 4, 4, 4);
    }

    /* Mode badge top-right */
    const char* mode_str = (app->app_mode == ModeBridge) ? "BRG"
                         : (app->app_mode == ModeMenu)   ? "MNU"
                                                         : "RDY";
    canvas_draw_box(canvas, 104, 1, 23, 10);
    canvas_set_color(canvas, ColorBlack);
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 106, 9, mode_str);

    canvas_set_color(canvas, ColorBlack);

    /* ── MENU mode — two-column icon grid ────────────────────────── */
    if(app->app_mode == ModeMenu) {
        /* Thin separator below header */
        canvas_draw_line(canvas, 0, 13, 128, 13);

        uint8_t sel = app->menu_index;
        /* Show 4 rows × 2 columns = 8 items per page */
        uint8_t page_start = (sel / 8) * 8;
        if(page_start + 8 > MENU_COUNT) page_start = (MENU_COUNT >= 8) ? MENU_COUNT - 8 : 0;

        for(uint8_t i = 0; i < 8 && (page_start + i) < MENU_COUNT; i++) {
            uint8_t idx = page_start + i;
            uint8_t col = i % 2;
            uint8_t row = i / 2;
            uint8_t cx  = col ? 64 : 0;
            uint8_t cy  = 14 + row * 13;
            bool is_sel = (idx == sel);

            if(is_sel) {
                canvas_set_color(canvas, ColorBlack);
                canvas_draw_box(canvas, cx, cy, 63, 12);
                canvas_set_color(canvas, ColorWhite);
            } else {
                canvas_set_color(canvas, ColorBlack);
            }

            canvas_set_font(canvas, FontSecondary);
            draw_menu_icon(canvas, idx, cx + 1, cy + 9);
            canvas_draw_str(canvas, cx + 22, cy + 9, MENU_TITLES[idx]);
            canvas_set_color(canvas, ColorBlack);

            /* Grid dividers */
            if(col == 0) canvas_draw_line(canvas, 63, cy, 63, cy + 12);
        }

        /* Page indicator dots */
        uint8_t pages = (MENU_COUNT + 7) / 8;
        uint8_t cur_page = page_start / 8;
        for(uint8_t p = 0; p < pages; p++) {
            uint8_t px = 56 + p * 5;
            if(p == cur_page) canvas_draw_box(canvas, px, 61, 3, 3);
            else              canvas_draw_frame(canvas, px, 61, 3, 3);
        }

        /* Nav hint */
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 0, 63, "^ v OK  BACK=quit");
        return;
    }

    /* ── BRIDGE mode — waveform + status ─────────────────────────── */
    if(app->app_mode == ModeBridge) {
        /* Waveform animation using spin phase */
        static const uint8_t WAVE_H[8] = {4,6,8,10,8,6,4,2};
        uint8_t phase = app->spin & 7;
        for(uint8_t i = 0; i < 16; i++) {
            uint8_t wh = WAVE_H[(i + phase) & 7];
            uint8_t wy = 32 - wh / 2;
            canvas_draw_box(canvas, 6 + i * 7, wy, 5, wh);
        }

        /* Status overlay pill */
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_box(canvas, 18, 42, 92, 11);
        canvas_set_color(canvas, ColorWhite);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 22, 50, "USB");
        canvas_draw_str(canvas, 44, 50, "<=>");
        canvas_draw_str(canvas, 66, 50, "UART");
        canvas_set_color(canvas, ColorBlack);

        /* Bottom hint */
        canvas_draw_str(canvas, 10, 63, "Hold OK 3s to exit");
        return;
    }

    /* ── JSON mode — terminal display ───────────────────────────── */
    /* Left border tick */
    canvas_draw_line(canvas, 0, 13, 0, 63);
    canvas_draw_line(canvas, 1, 13, 1, 63);

    canvas_set_font(canvas, FontSecondary);

    /* CMD line with > prompt */
    canvas_draw_str(canvas, 4, 22, ">");
    canvas_draw_str(canvas, 11, 22, app->cmd_disp[0] ? app->cmd_disp : "waiting...");

    /* Divider */
    canvas_draw_line(canvas, 4, 24, 112, 24);

    /* Status with spinner */
    const char* sp = SPIN_CHARS[app->spin & 3];
    canvas_draw_str(canvas, 4, 33, sp);
    canvas_draw_str(canvas, 11, 33, app->status[0] ? app->status : "idle");

    /* RX hex-style display */
    canvas_draw_str(canvas, 4, 43, "RX");
    canvas_draw_line(canvas, 4, 44, 18, 44);
    canvas_draw_str(canvas, 4, 53, app->rx_disp[0] ? app->rx_disp : "---");

    /* Vertical progress bar on right edge */
    canvas_draw_frame(canvas, 118, 14, 8, 48);
    if(app->progress > 0) {
        uint8_t ph = (app->progress > 100) ? 46
                   : (uint8_t)((app->progress * 46) / 100);
        canvas_draw_box(canvas, 119, 14 + (46 - ph), 6, ph);
    }
    /* % label */
    if(app->progress > 0) {
        char pct[5];
        snprintf(pct, sizeof(pct), "%u%%", (unsigned)(app->progress > 100 ? 100 : app->progress));
        canvas_draw_str(canvas, 113, 63, pct);
    }
}


static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(!app->init_done || ev->type != InputTypeShort) return;
    if(ev->key == InputKeyUp) {
        if(app->app_mode == ModeMenu) app->menu_index = (app->menu_index + MENU_COUNT - 1) % MENU_COUNT;
    } else if(ev->key == InputKeyDown) {
        if(app->app_mode == ModeMenu) app->menu_index = (app->menu_index + 1) % MENU_COUNT;
    } else if(ev->key == InputKeyOk) {
        if(app->app_mode == ModeBridge) { app->bridge_exit_tick = furi_get_tick() + furi_ms_to_ticks(3000); }
        else if(app->app_mode == ModeMenu) { app->app_mode = ModeJson; }
    } else if(ev->key == InputKeyBack) {
        if(app->app_mode != ModeMenu) { app->app_mode = ModeMenu; app->menu_index = 0; }
        else { furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop); }
    }
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
    if(app->hw_state == HwNfcDetect ||
       app->hw_state == HwNfcRead   ||
       app->hw_state == HwNfcEmulate) {
        if(app->nfc_scanner) {
            nfc_scanner_stop(app->nfc_scanner);
            nfc_scanner_free(app->nfc_scanner);
            app->nfc_scanner = NULL;
        }
        if(app->nfc_poller) {
            nfc_poller_stop(app->nfc_poller);
            nfc_poller_free(app->nfc_poller);
            app->nfc_poller = NULL;
        }
        if(app->nfc_listener) {
            nfc_listener_stop(app->nfc_listener);
            nfc_listener_free(app->nfc_listener);
            app->nfc_listener = NULL;
        }
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
            "{\"status\":\"ok\",\"type\":\"%s\",\"data\":\"%s\",\"id\":\"%s\"}\n",
            name ? name : "RFID", hex, app->hw_id);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* Infrared RX */
static void ir_rx_cb(void* ctx, InfraredWorkerSignal* signal) {
    Pen15App* app = ctx;
    if(infrared_worker_signal_is_decoded(signal)) {
        const InfraredMessage* msg = infrared_worker_get_decoded_signal(signal);
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"protocol\":\"%s\",\"address\":%lu,\"command\":%lu,\"id\":\"%s\"}\n",
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
        "{\"status\":\"ok\",\"type\":\"%s\",\"data\":\"%s\",\"id\":\"%s\"}\n",
        name ? name : "iButton", furi_string_get_cstr(data_str), app->hw_id);

    furi_string_free(data_str);
    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
}

/* NFC phase-1 scanner — detects protocol only, fires EvtNfcPhase2 */
static void nfc_scanner_cb(NfcScannerEvent event, void* ctx) {
    Pen15App* app = ctx;
    if(event.type == NfcScannerEventTypeDetected) {
        app->nfc_detected_proto = (event.data.protocol_num > 0)
            ? event.data.protocols[0]
            : NfcProtocolInvalid;
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtNfcPhase2);
    }
}

/* NFC phase-2 poller — reads real ISO14443-3A UID bytes */
static NfcCommand nfc_poller_cb(NfcPollerEvent event, void* ctx) {
    Pen15App* app = ctx;
    if(app->hw_state != HwNfcRead) return NfcCommandStop;

    Iso14443_3aPollerEvent* iso_ev = (Iso14443_3aPollerEvent*)&event;

    if(iso_ev->type == Iso14443_3aPollerEventTypeReady) {
        const Iso14443_3aData* d = iso_ev->data;
        char uid_hex[24] = {0};
        for(uint8_t i = 0; i < d->uid_len && i < 10; i++)
            snprintf(uid_hex + i * 2, 3, "%02X", d->uid[i]);

        const char* proto_name = nfc_device_get_protocol_name(NfcProtocolIso14443_3a);
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"type\":\"%s\",\"uid\":\"%s\","
            "\"atqa\":\"%04X\",\"sak\":\"%02X\",\"id\":\"%s\"}\n",
            proto_name ? proto_name : "ISO14443-3A",
            uid_hex,
            (unsigned)d->atqa,
            (unsigned)d->sak,
            app->hw_id);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
        return NfcCommandStop;
    }

    return NfcCommandContinue;
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
            /* Format timings as comma-separated signed ints */
            char* p = app->hw_result_json;
            int remaining = (int)sizeof(app->hw_result_json);
            int written = snprintf(p, (size_t)remaining,
                "{\"status\":\"ok\",\"count\":%zu,\"timings\":\"", app->rx_timings_count);
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
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"count\":%u,\"timings\":\"\",\"id\":\"%s\"}\n",
            (unsigned)app->subghz_rx_count, app->hw_id);
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

    app->progress = 50;
    app->spin++;
    strncpy(app->cmd_disp, action, sizeof(app->cmd_disp) - 1);
    view_port_update(app->vp);

    /* ── ping ──────────────────────────────────────────────────────── */
    if(strcmp(action, "ping") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap\":\"2.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status,  "CONN", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "ping ok", sizeof(app->rx_disp) - 1);
        app->progress = 100;

    /* ── gpio_mode ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_mode") == 0) {
        int  pin  = json_int(js, toks, n, "pin", -1);
        char mode[16] = {0};
        json_str(js, toks, n, "mode", mode, sizeof(mode));
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else {
            if(strcmp(mode, "output") == 0) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeOutputPushPull, GpioPullNo, GpioSpeedMedium);
                app->pin_mode[pin] = PinOutput;
            } else {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput, GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"mode\":\"%s\",\"id\":\"%s\"}\n", pin, mode, id);
        }
        app->progress = 100; usb_send(app, resp);

    /* ── gpio_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_write") == 0) {
        int pin   = json_int(js, toks, n, "pin",   -1);
        int value = json_int(js, toks, n, "value", -1);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else if(app->pin_mode[pin] != PinOutput) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"NOT_OUTPUT\","
                "\"message\":\"Call gpio_mode output first\",\"id\":\"%s\"}\n", id);
        } else {
            furi_hal_gpio_write(EXT_PINS[pin], value != 0);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, value != 0 ? 1 : 0, id);
        }
        app->progress = 100; usb_send(app, resp);

    /* ── gpio_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_read") == 0) {
        int pin = json_int(js, toks, n, "pin", -1);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else {
            if(app->pin_mode[pin] == PinUnset) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput, GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            bool val = furi_hal_gpio_read(EXT_PINS[pin]);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, val ? 1 : 0, id);
        }
        app->progress = 100; usb_send(app, resp);

    /* ── uart_init ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_init") == 0) {
        int baud = json_int(js, toks, n, "baud", 115200);
        bool uart_ok = false;
        if(!app->uart_ready) {
            app->serial = furi_hal_serial_control_acquire(FuriHalSerialIdUsart);
            if(app->serial) {
                furi_hal_serial_init(app->serial, (uint32_t)baud);
                furi_hal_serial_dma_rx_start(app->serial, uart_rx_dma_cb, app, false);
                app->uart_ready = true;
                uart_ok = true;
                snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"baud\":%d,\"id\":\"%s\"}\n", baud, id);
            } else {
                snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"UART_BUSY\",\"id\":\"%s\"}\n", id);
            }
        } else {
            furi_hal_serial_set_br(app->serial, (uint32_t)baud);
            uart_ok = true;
            snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"baud\":%d,\"id\":\"%s\"}\n", baud, id);
        }
        app->progress = 100; usb_send(app, resp);
        if(uart_ok) {
            app->bridge_mode = true;
        app->bridge_exit_tick = furi_get_tick() + furi_ms_to_ticks(3000);
            strncpy(app->status, "BRIDGE", sizeof(app->status) - 1);
            strncpy(app->rx_disp, "awok bridge", sizeof(app->rx_disp) - 1);
        }

    /* ── uart_send ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_send") == 0) {
        static char data[128]; memset(data, 0, sizeof(data));
        json_str(js, toks, n, "data", data, sizeof(data));
        if(!app->uart_ready) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"UART_NOT_INIT\",\"id\":\"%s\"}\n", id);
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
                "{\"status\":\"ok\",\"uart_rx\":\"%s\",\"id\":\"%s\"}\n", awok, id);
            app->progress = 100; usb_send(app, resp);
            strncpy(app->rx_disp, awok_len > 0 ? awok : "(no rx)", sizeof(app->rx_disp) - 1);
        }

    /* ── get_device_info ───────────────────────────────────────────── */
    } else if(strcmp(action, "get_device_info") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap_ver\":\"2.0\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);

    /* ── hw_stop ───────────────────────────────────────────────────── */
    } else if(strcmp(action, "hw_stop") == 0) {
        hw_stop_all(app);
        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->rx_disp, "hw stop", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"reading\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status, "RFID", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "rfid reading", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"scanning\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status, "NFC", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "nfc scanning", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"reading\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status, "IR", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "ir learning", sizeof(app->rx_disp) - 1);

    /* ── ir_tx ─────────────────────────────────────────────────────── */
    } else if(strcmp(action, "ir_tx") == 0) {
        char proto_name[32] = {0};
        json_str(js, toks, n, "protocol", proto_name, sizeof(proto_name));
        long long address = json_ll(js, toks, n, "address", 0);
        long long command = json_ll(js, toks, n, "command", 0);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->rx_disp, "ir tx ok", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"reading\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status, "KEY", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "ikey reading", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"scanning\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status, "RF RX", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "subghz rx", sizeof(app->rx_disp) - 1);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"recording\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status,  "RF REC",    sizeof(app->status)  - 1);
        strncpy(app->rx_disp, "subghz rec", sizeof(app->rx_disp) - 1);

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

        strncpy(app->status,  "RF TX",   sizeof(app->status)  - 1);
        strncpy(app->rx_disp, "tx raw",  sizeof(app->rx_disp) - 1);

    /* ── storage_write ─────────────────────────────────────────────── */
    } else if(strcmp(action, "storage_write") == 0) {
        static char path[128];    memset(path, 0, sizeof(path));
        static char content[1024]; memset(content, 0, sizeof(content));
        json_str(js, toks, n, "path",    path,    sizeof(path));
        json_str(js, toks, n, "content", content, sizeof(content));
        json_unescape(content);

        bool ok = storage_write_file(path, content, strlen(content));
        snprintf(resp, sizeof(resp), "{\"status\":\"%s\",\"id\":\"%s\"}\n",
            ok ? "ok" : "error", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->rx_disp, ok ? "write ok" : "write err", sizeof(app->rx_disp) - 1);

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
            "{\"status\":\"%s\",\"content\":\"%s\",\"id\":\"%s\"}\n",
            got > 0 ? "ok" : "error", escaped, id);
        app->progress = 100; usb_send(app, resp);

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

        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->status,  "RFID EM", sizeof(app->status)  - 1);
        strncpy(app->rx_disp, "rfid emulate", sizeof(app->rx_disp) - 1);

    /* ── ikey_emulate ─────────────────────────────────────────────── */
    } else if(strcmp(action, "ikey_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        /* Build key from JSON type+data if not already set by prior ikey_read */
        char ikey_type[32] = {0};
        char ikey_data[64] = {0};
        json_str(js, toks, n, "type", ikey_type, sizeof(ikey_type));
        json_str(js, toks, n, "data", ikey_data, sizeof(ikey_data));

        if(strlen(ikey_type) > 0 && strlen(ikey_data) > 0) {
            if(app->ibutton_key) { ibutton_key_free(app->ibutton_key); app->ibutton_key = NULL; }
            if(app->ibutton_protocols) { ibutton_protocols_free(app->ibutton_protocols); app->ibutton_protocols = NULL; }
            app->ibutton_protocols = ibutton_protocols_alloc();

            iButtonProtocolId pid = 0;
            bool pid_found = false;
            for(iButtonProtocolId i = 0; i < ibutton_protocols_get_count(app->ibutton_protocols); i++) {
                const char* pn = ibutton_protocols_get_name(app->ibutton_protocols, i);
                if(pn && strcmp(pn, ikey_type) == 0) { pid = i; pid_found = true; break; }
            }
            (void)pid_found;

            app->ibutton_key = ibutton_key_alloc(64);
            ibutton_key_set_protocol_id(app->ibutton_key, pid);

            uint8_t raw[16]; size_t raw_len = 0;
            const char* hp = ikey_data;
            while(*hp && raw_len < sizeof(raw)) {
                while(*hp == ' ' || *hp == ':') hp++;
                if(!*hp) break;
                char bs[3] = { hp[0], hp[1] ? hp[1] : '0', '\0' };
                raw[raw_len++] = (uint8_t)strtoul(bs, NULL, 16);
                hp += 2;
            }
            if(raw_len > 0) {
                uint8_t* kd = ibutton_key_get_data_p(app->ibutton_key);
                if(kd) memcpy(kd, raw, raw_len);
            }
        }

        if(!app->ibutton_key) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_KEY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        if(!app->ibutton_protocols) app->ibutton_protocols = ibutton_protocols_alloc();
        app->ibutton_worker = ibutton_worker_alloc(app->ibutton_protocols);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        ibutton_worker_emulate_start(app->ibutton_worker, app->ibutton_key);
        app->hw_state = HwIkeyEmulate;

        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->status,  "KEY EM", sizeof(app->status)  - 1);
        strncpy(app->rx_disp, "ikey emulate", sizeof(app->rx_disp) - 1);

    /* ── nfc_emulate ──────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        char uid_hex[32]  = {0};
        char atqa_hex[8]  = {0};
        char sak_hex[4]   = {0};
        json_str(js, toks, n, "uid",  uid_hex,  sizeof(uid_hex));
        json_str(js, toks, n, "atqa", atqa_hex, sizeof(atqa_hex));
        json_str(js, toks, n, "sak",  sak_hex,  sizeof(sak_hex));

        Iso14443_3aData iso_data;
        memset(&iso_data, 0, sizeof(iso_data));
        size_t hex_len = strlen(uid_hex);
        for(size_t i = 0; i + 1 < hex_len && iso_data.uid_len < 10; i += 2) {
            char bs[3] = { uid_hex[i], uid_hex[i+1], '\0' };
            iso_data.uid[iso_data.uid_len++] = (uint8_t)strtol(bs, NULL, 16);
        }
        if(iso_data.uid_len == 0) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"BAD_UID\",\"id\":\"%s\"}\n", id);
            app->progress = 0; usb_send(app, resp);
        } else {
            /* ATQA/SAK: use JSON-supplied values if present (Theory 17),
             * otherwise fall back to ISO14443-3A NTAG-compatible defaults. */
            iso_data.atqa = atqa_hex[0] ? (uint16_t)strtol(atqa_hex, NULL, 16) : 0x4400;
            iso_data.sak  = sak_hex[0]  ? (uint8_t)strtol(sak_hex,  NULL, 16) : 0x00;
            strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
            app->nfc = nfc_alloc();
            app->nfc_listener = nfc_listener_alloc(app->nfc, NfcProtocolIso14443_3a, &iso_data);
            nfc_listener_start(app->nfc_listener, NULL, NULL);
            app->hw_state = HwNfcEmulate;
            snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
            app->progress = 100; usb_send(app, resp);
            strncpy(app->status,  "NFC EM",     sizeof(app->status)  - 1);
            strncpy(app->rx_disp, "nfc emulate", sizeof(app->rx_disp) - 1);
        }

    /* ── nfc_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_write") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_write requires NFC app\",\"id\":\"%s\"}\n", id);
        app->progress = 0; usb_send(app, resp);

    /* ── unknown ───────────────────────────────────────────────────── */
    } else {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"UNKNOWN\","
            "\"message\":\"%s\",\"id\":\"%s\"}\n", action, id);
        app->progress = 0; usb_send(app, resp);
        strncpy(app->rx_disp, "unknown cmd", sizeof(app->rx_disp) - 1);
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
                handle_json(app, app->json_buf, app->json_len);
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

    app->thread      = furi_thread_get_current();
    app->usb_mtx     = furi_mutex_alloc(FuriMutexTypeNormal);
    app->tx_sem      = furi_semaphore_alloc(1, 1);
    app->uart_rx_buf = furi_stream_buffer_alloc(UART_RX_BUF, 1);
    app->hw_state    = HwIdle;

    strncpy(app->status,   "WAIT", sizeof(app->status)   - 1);
    app->app_mode = ModeJson;
    app->menu_index = 0;
    strncpy(app->cmd_disp, "---",  sizeof(app->cmd_disp) - 1);
    strncpy(app->rx_disp,  "---",  sizeof(app->rx_disp)  - 1);

    app->vp  = view_port_alloc();
    app->gui = furi_record_open(RECORD_GUI);
    view_port_draw_callback_set(app->vp,  draw_cb,  app);
    view_port_input_callback_set(app->vp, input_cb, app);
    gui_add_view_port(app->gui, app->vp, GuiLayerFullscreen);

    app->cli_vcp = furi_record_open(RECORD_CLI_VCP);
    cli_vcp_disable(app->cli_vcp);
    furi_hal_cdc_set_callbacks(0, (CdcCallbacks*)&CDC_CB, app);
    app->init_done = true;

    /* Main event loop */
    while(true) {
        uint32_t evts = furi_thread_flags_wait(ALL_EVENTS, FuriFlagWaitAny, 250);

        if(evts & FuriFlagError) {
            /* Timeout tick — check hw deadline */
            if(app->hw_state != HwIdle &&
               furi_get_tick() > app->hw_deadline_tick) {
                static char tout[64];
                snprintf(tout, sizeof(tout),
                    "{\"status\":\"error\",\"code\":\"TIMEOUT\",\"id\":\"%s\"}\n",
                    app->hw_id);
                hw_stop_all(app);
                usb_send(app, tout);
                strncpy(app->rx_disp, "hw timeout", sizeof(app->rx_disp) - 1);
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
        app->app_mode = ModeJson;
            strncpy(app->status,   "WAIT",       sizeof(app->status)   - 1);
            strncpy(app->rx_disp,  "bridge off", sizeof(app->rx_disp)  - 1);
            app->progress = 0;
            view_port_update(app->vp);
        }

        /* NFC phase 2: scanner detected protocol, now start poller for real UID */
        if(evts & EvtNfcPhase2) {
            if(app->hw_state == HwNfcDetect && app->nfc_scanner) {
                nfc_scanner_stop(app->nfc_scanner);
                nfc_scanner_free(app->nfc_scanner);
                app->nfc_scanner = NULL;

                if(app->nfc_detected_proto == NfcProtocolIso14443_3a) {
                    app->nfc_poller = nfc_poller_alloc(app->nfc, NfcProtocolIso14443_3a);
                    nfc_poller_start(app->nfc_poller, nfc_poller_cb, app);
                    app->hw_state = HwNfcRead;
                    strncpy(app->rx_disp, "nfc uid read", sizeof(app->rx_disp) - 1);
                } else {
                    /* Non-ISO14443-3A: return protocol name + proto-index as UID */
                    NfcProtocol proto = app->nfc_detected_proto;
                    const char* pname = (proto != NfcProtocolInvalid)
                        ? nfc_device_get_protocol_name(proto) : "NFC";
                    char uid_str[8];
                    snprintf(uid_str, sizeof(uid_str), "%02X", (unsigned)proto);
                    snprintf(app->hw_result_json, sizeof(app->hw_result_json),
                        "{\"status\":\"ok\",\"type\":\"%s\",\"uid\":\"%s\",\"id\":\"%s\"}\n",
                        pname ? pname : "NFC", uid_str, app->hw_id);
                    if(app->nfc) { nfc_free(app->nfc); app->nfc = NULL; }
                    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
                }
            }
        }

        if(evts & EvtHwDone) {
            /* Hardware read completed — result already in hw_result_json */
            hw_stop_all(app);
            usb_send(app, app->hw_result_json);
            app->progress = 100;
            strncpy(app->rx_disp, "hw done", sizeof(app->rx_disp) - 1);
        }

        if(evts & EvtTxDone) {
            /* Async TX finished */
            furi_hal_subghz_stop_async_tx();
            furi_hal_subghz_sleep();
            app->hw_state = HwIdle;
            static char txresp[64];
            snprintf(txresp, sizeof(txresp),
                "{\"status\":\"ok\",\"id\":\"%s\"}\n", app->hw_id);
            usb_send(app, txresp);
            app->progress = 100;
            strncpy(app->rx_disp, "tx done", sizeof(app->rx_disp) - 1);
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
