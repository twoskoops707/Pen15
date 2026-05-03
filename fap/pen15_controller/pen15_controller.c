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
    char   rx_disp[DISP_STR_LEN];
    uint8_t progress;
    uint8_t spin;

    /* ── Verification / activity counters (for UI) ─────────────────── */
    uint32_t cmd_count;         /* total JSON commands handled */
    uint32_t usb_rx_bytes;      /* lifetime USB bytes in */
    uint32_t usb_tx_bytes;      /* lifetime USB bytes out */
    uint32_t uart_rx_bytes;     /* lifetime UART bytes from AWOK */
    uint32_t uart_tx_bytes;     /* lifetime UART bytes to AWOK */
    uint32_t hw_start_tick;     /* when current HW op started */
    uint32_t last_usb_rx_tick;  /* for USB blink */
    uint32_t last_usb_tx_tick;  /* for USB blink */
    uint32_t last_uart_rx_tick; /* for bridge blink */
    uint32_t last_uart_tx_tick; /* for bridge blink */
    uint32_t last_resp_tick;    /* for footer flash */
    uint32_t boot_tick;         /* app start */
    uint32_t subghz_freq;       /* last set RF frequency */
    bool     link_up;           /* USB DTR asserted (host present) */
    bool     last_resp_ok;      /* for footer color */

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

static const char* SPIN_CHARS[] __attribute__((unused)) = {"|", "/", "-", "\\"};

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
static void cdc_state_cb(void* ctx, uint8_t s) {
    Pen15App* app = ctx;
    app->link_up = (s != 0);
}
static void cdc_ctrl_cb(void* ctx, uint8_t s) {
    Pen15App* app = ctx;
    app->link_up = (s & 0x01) ? true : false;
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
    app->usb_tx_bytes += len;
    app->last_usb_tx_tick = furi_get_tick();
    /* sniff status of the JSON response for the footer indicator */
    if(strstr(str, "\"status\":\"ok\"") || strstr(str, "\"status\":\"reading\"") ||
       strstr(str, "\"status\":\"scanning\"") || strstr(str, "\"status\":\"recording\"")) {
        app->last_resp_ok = true;
    } else if(strstr(str, "\"status\":\"error\"")) {
        app->last_resp_ok = false;
    }
    app->last_resp_tick = furi_get_tick();
}

static void usb_send_raw(Pen15App* app, const uint8_t* data, uint16_t len) {
    if(len == 0) return;
    furi_semaphore_acquire(app->tx_sem, 300);
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    furi_hal_cdc_send(0, (uint8_t*)data, len);
    furi_mutex_release(app->usb_mtx);
    app->usb_tx_bytes += len;
    app->last_usb_tx_tick = furi_get_tick();
}

/* ═══════════════════════════════════════════════════════════════════
   GUI — verification / live-activity layout
   128x64 mono. Every operation gets a context-specific renderer so the
   user can SEE that something is happening, even for invisible stuff
   like radio waves or iButton 1-wire pulses.

   Layout:
     y  0..10  status bar (title | link dot | mode badge | uptime)
     y 11      divider
     y 12..41  context view (per HwState / bridge)
     y 42..51  meter row (USB rx/tx + UART rx/tx + cmd#)
     y 52..63  footer ACK strip (last cmd  ->  ok/err, inverted)
   ═══════════════════════════════════════════════════════════════════ */
static const char* hw_state_label(HwState s, AppMode m) {
    if(m == ModeBridge) return "BRIDGE";
    switch(s) {
        case HwRfidRead:      return "RFID";
        case HwRfidEmulate:   return "RFID-EM";
        case HwNfcDetect:     return "NFC";
        case HwIrRx:          return "IR";
        case HwIkeyRead:      return "iKEY";
        case HwIkeyEmulate:   return "iKEY-EM";
        case HwSubghzRx:      return "RF-RX";
        case HwSubghzRecord:  return "RF-REC";
        case HwSubghzTx:      return "RF-TX";
        default:              return "READY";
    }
}

static void fmt_elapsed(char* buf, size_t sz, uint32_t start_tick) {
    if(start_tick == 0) { snprintf(buf, sz, "--:--"); return; }
    uint32_t now = furi_get_tick();
    uint32_t ms  = (now > start_tick) ? (now - start_tick) : 0;
    uint32_t s   = ms / furi_ms_to_ticks(1000);
    snprintf(buf, sz, "%02lu:%02lu", (unsigned long)(s / 60), (unsigned long)(s % 60));
}

static void fmt_bytes(char* buf, size_t sz, uint32_t n) {
    if(n < 1000)          snprintf(buf, sz, "%luB",  (unsigned long)n);
    else if(n < 1000000)  snprintf(buf, sz, "%luK",  (unsigned long)(n / 1000));
    else                  snprintf(buf, sz, "%luM",  (unsigned long)(n / 1000000));
}

/* Draw an animated concentric ripple — signals "field active" for RFID/NFC */
static void draw_field_ripple(Canvas* canvas, int cx, int cy, uint8_t spin) {
    uint8_t phase = spin & 3;
    for(int i = 0; i < 3; i++) {
        int r = 3 + i * 5 + phase;
        if(r < 18) canvas_draw_circle(canvas, cx, cy, r);
    }
    canvas_draw_disc(canvas, cx, cy, 2);
}

/* Draw an RSSI-like bar graph driven by a counter that really advances
   every time the radio captures a real edge. If the bars rise, real RF
   is reaching the chip. */
static void draw_rssi_bars(Canvas* canvas, int x, int y, uint32_t count, uint8_t spin) {
    for(int i = 0; i < 10; i++) {
        int h = (int)((count + i * 3 + spin) % 14);
        if(h < 2) h = 2;
        canvas_draw_box(canvas, x + i * 5, y + (14 - h), 3, h);
    }
}

/* Pulse train for SubGHz TX — shows advancing TX index as a wave */
static void draw_pulse_train(Canvas* canvas, int x, int y, int w, int h,
                              size_t tx_idx, size_t tx_count) {
    if(tx_count == 0) return;
    int mid = y + h / 2;
    canvas_draw_line(canvas, x, mid, x + w, mid);
    int step = 4;
    for(int i = 0; i < w / step; i++) {
        int top = ((tx_idx + i) % 2 == 0) ? mid - h / 2 : mid;
        canvas_draw_line(canvas, x + i * step,       top, x + i * step,       mid);
        canvas_draw_line(canvas, x + i * step,       top, x + i * step + step - 1, top);
        canvas_draw_line(canvas, x + i * step + step - 1, top, x + i * step + step - 1, mid);
    }
}

/* Bridge: bidirectional data flow arrows with animated direction */
static void draw_bridge_flow(Canvas* canvas, Pen15App* app) {
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 2,  22, "PHONE");
    canvas_draw_str(canvas, 98, 22, "AWOK");

    uint32_t now = furi_get_tick();
    bool usb_in_recent  = (app->last_usb_rx_tick  && (now - app->last_usb_rx_tick)  < furi_ms_to_ticks(300));
    bool uart_out_recent = (app->last_uart_tx_tick && (now - app->last_uart_tx_tick) < furi_ms_to_ticks(300));
    bool uart_in_recent  = (app->last_uart_rx_tick && (now - app->last_uart_rx_tick) < furi_ms_to_ticks(300));

    /* top arrow: phone -> awok */
    int y1 = 27;
    canvas_draw_line(canvas, 32, y1, 92, y1);
    canvas_draw_line(canvas, 90, y1 - 2, 92, y1);
    canvas_draw_line(canvas, 90, y1 + 2, 92, y1);
    if(usb_in_recent || uart_out_recent) {
        int step = (app->spin * 6) % 56;
        canvas_draw_box(canvas, 34 + step, y1 - 1, 4, 3);
    }

    /* bottom arrow: awok -> phone */
    int y2 = 35;
    canvas_draw_line(canvas, 32, y2, 92, y2);
    canvas_draw_line(canvas, 32, y2, 34, y2 - 2);
    canvas_draw_line(canvas, 32, y2, 34, y2 + 2);
    if(uart_in_recent) {
        int step = (app->spin * 6) % 56;
        canvas_draw_box(canvas, 88 - step, y2 - 1, 4, 3);
    }

    /* UART byte totals centered */
    char b1[20], b2[20];
    fmt_bytes(b1, sizeof(b1), app->uart_tx_bytes);
    fmt_bytes(b2, sizeof(b2), app->uart_rx_bytes);
    char line[24];
    snprintf(line, sizeof(line), "TX:%s  RX:%s", b1, b2);
    canvas_draw_str(canvas, 18, 41, line);
}

static void draw_progress_bar(Canvas* canvas, int x, int y, int w, int h, int pct) {
    if(pct < 0) pct = 0;
    if(pct > 100) pct = 100;
    canvas_draw_frame(canvas, x, y, w, h);
    int fill = ((w - 2) * pct) / 100;
    if(fill > 0) canvas_draw_box(canvas, x + 1, y + 1, fill, h - 2);
}

static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);

    /* ── Top status bar ──────────────────────────────────────────── */
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "PEN15");

    /* Link indicator: filled dot = USB DTR asserted (host present) */
    if(app->link_up) canvas_draw_disc(canvas, 36, 5, 3);
    else             canvas_draw_circle(canvas, 36, 5, 3);

    /* Mode badge (inverted rounded box at right) */
    const char* mode = hw_state_label(app->hw_state, app->app_mode);
    canvas_set_font(canvas, FontSecondary);
    int mode_w = canvas_string_width(canvas, mode) + 6;
    int mode_x = 126 - mode_w;
    canvas_draw_rbox(canvas, mode_x, 0, mode_w, 11, 2);
    canvas_set_color(canvas, ColorWhite);
    canvas_draw_str(canvas, mode_x + 3, 9, mode);
    canvas_set_color(canvas, ColorBlack);

    /* Uptime or current-op elapsed between link dot and badge */
    char tbuf[12];
    if(app->hw_state != HwIdle && app->hw_start_tick) {
        fmt_elapsed(tbuf, sizeof(tbuf), app->hw_start_tick);
    } else {
        fmt_elapsed(tbuf, sizeof(tbuf), app->boot_tick);
    }
    canvas_draw_str(canvas, 45, 9, tbuf);

    /* Divider */
    canvas_draw_line(canvas, 0, 11, 127, 11);

    /* ── Context view (y 12..41) ─────────────────────────────────── */
    if(app->app_mode == ModeBridge) {
        draw_bridge_flow(canvas, app);
    } else switch(app->hw_state) {
        case HwIdle: {
            canvas_set_font(canvas, FontPrimary);
            canvas_draw_str(canvas, 2, 22, "READY");
            canvas_set_font(canvas, FontSecondary);
            char cbuf[28];
            snprintf(cbuf, sizeof(cbuf), "cmds: %lu", (unsigned long)app->cmd_count);
            canvas_draw_str(canvas, 2, 32, cbuf);
            if(app->cmd_disp[0]) {
                char lbuf[28];
                snprintf(lbuf, sizeof(lbuf), "last: %s", app->cmd_disp);
                canvas_draw_str(canvas, 2, 41, lbuf);
            } else {
                canvas_draw_str(canvas, 2, 41, "awaiting USB...");
            }
            break;
        }
        case HwRfidRead:
        case HwRfidEmulate: {
            draw_field_ripple(canvas, 18, 27, app->spin);
            canvas_set_font(canvas, FontPrimary);
            canvas_draw_str(canvas, 40, 22,
                app->hw_state == HwRfidRead ? "LF FIELD" : "LF EMU");
            canvas_set_font(canvas, FontSecondary);
            canvas_draw_str(canvas, 40, 32,
                app->hw_state == HwRfidRead ? "scanning 125kHz" : "broadcasting");
            canvas_draw_str(canvas, 40, 41, "present card...");
            break;
        }
        case HwNfcDetect: {
            draw_field_ripple(canvas, 18, 27, app->spin);
            canvas_set_font(canvas, FontPrimary);
            canvas_draw_str(canvas, 40, 22, "HF FIELD");
            canvas_set_font(canvas, FontSecondary);
            canvas_draw_str(canvas, 40, 32, "scanning 13.56M");
            canvas_draw_str(canvas, 40, 41, "present tag...");
            break;
        }
        case HwIrRx: {
            canvas_set_font(canvas, FontPrimary);
            canvas_draw_str(canvas, 2, 22, "IR LEARN");
            /* IR beam animation */
            canvas_set_font(canvas, FontSecondary);
            for(int i = 0; i < 6; i++) {
                int bx = 60 + ((app->spin + i * 3) % 60);
                canvas_draw_box(canvas, bx, 20, 2, 2);
            }
            canvas_draw_str(canvas, 2, 32, "point remote");
            canvas_draw_str(canvas, 2, 41, "press any key");
            break;
        }
        case HwIkeyRead:
        case HwIkeyEmulate: {
            canvas_set_font(canvas, FontPrimary);
            canvas_draw_str(canvas, 2, 22,
                app->hw_state == HwIkeyRead ? "iBUTTON" : "iKEY EMU");
            /* 1-wire pulse train */
            int px = 2, py = 30;
            for(int i = 0; i < 24; i++) {
                int h = ((app->spin + i) & 1) ? 0 : 6;
                canvas_draw_line(canvas, px + i * 5, py + 6,
                                         px + i * 5, py + 6 - h);
                canvas_draw_line(canvas, px + i * 5, py + 6 - h,
                                         px + i * 5 + 4, py + 6 - h);
                canvas_draw_line(canvas, px + i * 5 + 4, py + 6 - h,
                                         px + i * 5 + 4, py + 6);
            }
            canvas_set_font(canvas, FontSecondary);
            canvas_draw_str(canvas, 70, 22,
                app->hw_state == HwIkeyRead ? "touch key" : "touch reader");
            break;
        }
        case HwSubghzRx:
        case HwSubghzRecord: {
            canvas_set_font(canvas, FontSecondary);
            char fbuf[24];
            if(app->subghz_freq > 0)
                snprintf(fbuf, sizeof(fbuf), "%lu.%02lu MHz",
                    (unsigned long)(app->subghz_freq / 1000000),
                    (unsigned long)((app->subghz_freq / 10000) % 100));
            else
                snprintf(fbuf, sizeof(fbuf), "433.92 MHz");
            canvas_draw_str(canvas, 2, 20, fbuf);

            char ebuf[24];
            snprintf(ebuf, sizeof(ebuf), "edges: %lu",
                (unsigned long)app->subghz_rx_count);
            canvas_draw_str(canvas, 70, 20, ebuf);

            /* Live RSSI-style bars driven by real edge count */
            draw_rssi_bars(canvas, 2, 22, app->subghz_rx_count, app->spin);

            if(app->hw_state == HwSubghzRecord) {
                int pct = (int)((app->rx_timings_count * 100) / RX_MAX_TIMES);
                draw_progress_bar(canvas, 2, 38, 124, 4, pct);
            }
            break;
        }
        case HwSubghzTx: {
            canvas_set_font(canvas, FontSecondary);
            char fbuf[24];
            if(app->subghz_freq > 0)
                snprintf(fbuf, sizeof(fbuf), "%lu.%02lu MHz",
                    (unsigned long)(app->subghz_freq / 1000000),
                    (unsigned long)((app->subghz_freq / 10000) % 100));
            else
                snprintf(fbuf, sizeof(fbuf), "433.92 MHz");
            canvas_draw_str(canvas, 2, 20, fbuf);

            char bbuf[24];
            int rep = app->tx_repeat > 0 ? app->tx_repeat : 1;
            snprintf(bbuf, sizeof(bbuf), "burst %d/%d",
                app->tx_repeat_cnt + 1, rep);
            canvas_draw_str(canvas, 70, 20, bbuf);

            draw_pulse_train(canvas, 2, 22, 124, 12, app->tx_idx, app->tx_count);

            int pct = app->tx_count > 0
                ? (int)((app->tx_idx * 100) / app->tx_count)
                : 0;
            draw_progress_bar(canvas, 2, 38, 124, 4, pct);
            break;
        }
    }

    /* ── Meter row (y 42..51) ────────────────────────────────────── */
    canvas_draw_line(canvas, 0, 43, 127, 43);
    canvas_set_font(canvas, FontSecondary);

    uint32_t now = furi_get_tick();
    bool usb_rx_blink = (app->last_usb_rx_tick && (now - app->last_usb_rx_tick) < furi_ms_to_ticks(150));
    bool usb_tx_blink = (app->last_usb_tx_tick && (now - app->last_usb_tx_tick) < furi_ms_to_ticks(150));

    /* USB in/out meters */
    if(usb_rx_blink) canvas_draw_disc(canvas, 4, 49, 2);
    else             canvas_draw_circle(canvas, 4, 49, 2);
    char usb_in[12]; fmt_bytes(usb_in, sizeof(usb_in), app->usb_rx_bytes);
    char usb_line[24]; snprintf(usb_line, sizeof(usb_line), "in %s", usb_in);
    canvas_draw_str(canvas, 9, 51, usb_line);

    if(usb_tx_blink) canvas_draw_disc(canvas, 52, 49, 2);
    else             canvas_draw_circle(canvas, 52, 49, 2);
    char usb_out[12]; fmt_bytes(usb_out, sizeof(usb_out), app->usb_tx_bytes);
    char usb_oline[24]; snprintf(usb_oline, sizeof(usb_oline), "out %s", usb_out);
    canvas_draw_str(canvas, 57, 51, usb_oline);

    /* cmd count at right */
    char cline[16];
    snprintf(cline, sizeof(cline), "#%lu", (unsigned long)app->cmd_count);
    canvas_draw_str(canvas, 100, 51, cline);

    /* ── Footer ACK strip (y 52..63, inverted) ───────────────────── */
    canvas_draw_box(canvas, 0, 52, 128, 12);
    canvas_set_color(canvas, ColorWhite);

    char foot[40];
    if(app->cmd_count == 0) {
        snprintf(foot, sizeof(foot), "waiting for JSON on USB");
    } else {
        const char* tag = app->last_resp_ok ? "ACK" : "NAK";
        snprintf(foot, sizeof(foot), "%s %s -> %s",
            tag, app->cmd_disp[0] ? app->cmd_disp : "?",
            app->last_resp_ok ? "ok" : "err");
    }
    canvas_draw_str(canvas, 3, 61, foot);
    canvas_set_color(canvas, ColorBlack);
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

/* NFC scanner */
static void nfc_scanner_cb(NfcScannerEvent event, void* ctx) {
    Pen15App* app = ctx;
    if(event.type == NfcScannerEventTypeDetected) {
        const char* proto_name = "NFC";
        if(event.data.protocol_num > 0)
            proto_name = nfc_device_get_protocol_name(event.data.protocols[0]);

        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"type\":\"%s\",\"uid\":\"\",\"id\":\"%s\"}\n",
            proto_name, app->hw_id);

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
        app->hw_state = HwIdle;
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

    app->cmd_count++;
    app->progress = 50;
    app->spin++;
    memset(app->cmd_disp, 0, sizeof(app->cmd_disp));
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
        app->app_mode = ModeBridge;
        app->bridge_exit_tick = 0;
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
            size_t data_len = strlen(data);
            furi_hal_serial_tx(app->serial, (uint8_t*)data, data_len);
            furi_hal_serial_tx_wait_complete(app->serial);
            app->uart_tx_bytes += (uint32_t)data_len;
            app->last_uart_tx_tick = furi_get_tick();
            static char awok[200]; memset(awok, 0, sizeof(awok));
            size_t awok_len = 0;
            uint32_t deadline = furi_get_tick() + furi_ms_to_ticks(UART_RX_WAIT);
            while(furi_get_tick() < deadline && awok_len < sizeof(awok) - 1) {
                uint8_t b; size_t got = furi_stream_buffer_receive(app->uart_rx_buf, &b, 1, 0);
                if(got > 0) {
                    awok[awok_len++] = (char)b;
                    app->uart_rx_bytes++;
                    app->last_uart_rx_tick = furi_get_tick();
                } else {
                    furi_delay_ms(5);
                }
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
        app->hw_start_tick = furi_get_tick();

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
        app->hw_start_tick = furi_get_tick();

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
        app->hw_start_tick = furi_get_tick();

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
        app->hw_start_tick = furi_get_tick();

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
        app->hw_start_tick     = furi_get_tick();
        app->subghz_rx_count   = 0;
        app->subghz_freq       = (uint32_t)freq;

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
        app->hw_start_tick     = furi_get_tick();
        app->subghz_rx_count   = 0;
        app->subghz_freq       = (uint32_t)freq;
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
        app->hw_start_tick = furi_get_tick();
        app->subghz_freq   = (uint32_t)freq;

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
        if(!app->ibutton_key) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_KEY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp); return;
        }
        app->ibutton_protocols = ibutton_protocols_alloc();
        app->ibutton_worker    = ibutton_worker_alloc(app->ibutton_protocols);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        ibutton_worker_emulate_start(app->ibutton_worker, app->ibutton_key);
        app->hw_state = HwIkeyEmulate;

        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        strncpy(app->status,  "KEY EM", sizeof(app->status)  - 1);
        strncpy(app->rx_disp, "ikey emulate", sizeof(app->rx_disp) - 1);

    /* ── nfc_emulate ──────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_emulate") == 0) {
        char uid_hex[32] = {0};
        char type_str[32] = {0};
        json_str(js, toks, n, "uid",  uid_hex,  sizeof(uid_hex));
        json_str(js, toks, n, "type", type_str, sizeof(type_str));
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_emulate requires NFC app\",\"id\":\"%s\"}\n", id);
        app->progress = 0; usb_send(app, resp);

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

    if(got > 0) {
        app->usb_rx_bytes += (uint32_t)got;
        app->last_usb_rx_tick = furi_get_tick();
    }

    for(int32_t i = 0; i < got; i++) {
        char c = (char)buf[i];
        if(c == '\n' || c == '\r') {
            if(app->json_len > 0) {
                app->json_buf[app->json_len] = '\0';
        if(app->app_mode == ModeJson) {
                handle_json(app, app->json_buf, app->json_len);
            app->json_len = 0;
        }
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
    app->boot_tick = furi_get_tick();
    app->link_up = false;

    app->thread      = furi_thread_get_current();
    app->usb_mtx     = furi_mutex_alloc(FuriMutexTypeNormal);
    app->tx_sem      = furi_semaphore_alloc(1, 1);
    app->uart_rx_buf = furi_stream_buffer_alloc(UART_RX_BUF, 1);
    app->hw_state    = HwIdle;

    strncpy(app->status,   "WAIT", sizeof(app->status)   - 1);
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
                if(got > 0) {
                    app->usb_rx_bytes += (uint32_t)got;
                    app->last_usb_rx_tick = furi_get_tick();
                }
                if(got > 0 && app->uart_ready) {
                    furi_hal_serial_tx(app->serial, usb_buf, (size_t)got);
                    app->uart_tx_bytes += (uint32_t)got;
                    app->last_uart_tx_tick = furi_get_tick();
                }
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
                    if(got > 0) {
                        app->uart_rx_bytes += (uint32_t)got;
                        app->last_uart_rx_tick = furi_get_tick();
                        usb_send_raw(app, uart_buf, (uint16_t)got);
                    }
                } while(got > 0);
            }
        }

        if(evts & EvtBridgeExit) {
            strncpy(app->status,   "WAIT",       sizeof(app->status)   - 1);
            strncpy(app->rx_disp,  "bridge off", sizeof(app->rx_disp)  - 1);
            app->progress = 0;
            view_port_update(app->vp);
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
