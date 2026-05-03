/*
 * pen15_controller.c  — Pen15 FAP v3.0
 * Complete UI revamp: state-based animated visuals, signal counters,
 * elapsed-time display, and a result confirmation screen for every op.
 */

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
#define DISP_STR_LEN  32
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

/* ── UI screen mode ───────────────────────────────────────────────── */
typedef enum {
    UiIdle,        /* waiting for connection */
    UiConnected,   /* ping received, show ready */
    UiActive,      /* hw op in progress */
    UiResult,      /* op finished, showing result for 2s */
    UiBridge,      /* UART bridge mode */
} UiMode;

typedef enum { ModeJson, ModeMenu, ModeBridge } AppMode;

#define MENU_COUNT 9
static const char* MENU_TITLES[MENU_COUNT] __attribute__((unused)) = {
    "RFID Read", "NFC Detect", "SubGHz RX", "IR Learn", "iButton Read",
    "SubGHz TX", "UART Bridge", "GPIO Control", "Exit"
};
static const char* MENU_HINTS[MENU_COUNT] __attribute__((unused)) = {
    "READ", "DETECT", "RECORD", "LEARN", "READ", "TX", "BRIDGE", "GPIO", "EXIT"
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

    char   json_buf[JSON_BUF_SZ];
    size_t json_len;

    /* Legacy short display fields (kept for compatibility) */
    char   status[DISP_STR_LEN];
    char   cmd_disp[DISP_STR_LEN];
    char   rx_disp[DISP_STR_LEN];
    uint8_t progress;
    uint8_t spin;

    /* Extended UI state */
    UiMode   ui_mode;
    uint32_t op_start_tick;       /* tick when hw op began */
    uint32_t result_show_tick;    /* tick when result screen appeared */
    char     op_label[24];        /* e.g. "RFID READ" */
    char     op_detail[24];       /* e.g. freq, channel info */
    char     result_line1[22];    /* result line 1 */
    char     result_line2[22];    /* result line 2 */
    bool     result_ok;           /* green check vs red X */
    uint32_t sig_count;           /* live signal count for RF ops */

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

/* ═══════════════════════════════════════════════════════════════════
   GUI drawing helpers
   ═══════════════════════════════════════════════════════════════════ */

/* Horizontal dashed separator at y, from x0 to x1 */
static void draw_hline_dashed(Canvas* c, int x0, int x1, int y) {
    for(int x = x0; x < x1; x += 3) {
        canvas_draw_dot(c, x, y);
        canvas_draw_dot(c, x + 1, y);
    }
}

/* Draw centred text on a canvas (128 wide) */
static void draw_centred(Canvas* c, int y, const char* text) {
    int w = (int)canvas_string_width(c, text);
    int x = (128 - w) / 2;
    if(x < 0) x = 0;
    canvas_draw_str(c, x, y, text);
}

/* Draw a concentric ring of radius r centred at (cx,cy).
   Draws 4 cardinal dots per ring — cheap animation token. */
static void draw_ring(Canvas* c, int cx, int cy, int r) {
    if(r < 1) return;
    canvas_draw_dot(c, cx, cy - r);
    canvas_draw_dot(c, cx, cy + r);
    canvas_draw_dot(c, cx - r, cy);
    canvas_draw_dot(c, cx + r, cy);
    /* diagonals at 45° */
    int d = (r * 7) / 10;  /* ≈ r/√2 */
    if(d > 0) {
        canvas_draw_dot(c, cx + d, cy - d);
        canvas_draw_dot(c, cx - d, cy - d);
        canvas_draw_dot(c, cx + d, cy + d);
        canvas_draw_dot(c, cx - d, cy + d);
    }
}

/* Draw animated scan rings centred at (cx,cy).
   phase cycles 0..3 and produces expanding ring bursts. */
static void draw_scan_rings(Canvas* c, int cx, int cy, uint8_t phase) {
    /* 3 rings, each 6px apart, staggered by phase */
    for(int i = 0; i < 3; i++) {
        int base = ((int)phase + i * 4) % 12;  /* 12-step cycle */
        int r = base + 2;
        if(r > 14) r = 0;  /* fade out large rings */
        if(r > 0) draw_ring(c, cx, cy, r);
    }
}

/* Draw animated radio waves to the right of (cx,cy).
   Used for SubGHz RX/TX/Record. */
static void draw_rf_waves(Canvas* c, int cx, int cy, uint8_t phase) {
    /* 3 arcs expanding outward */
    for(int i = 0; i < 3; i++) {
        int offset = ((int)phase + i * 3) % 9;
        int r = 5 + offset * 2;
        if(r > 20) continue;
        /* Draw quarter-arc (top and bottom dots on right side) */
        for(int a = -4; a <= 4; a++) {
            int px = cx + r;
            int py = cy + a * r / 4;
            if(py >= 0 && py < 64 && px < 128)
                canvas_draw_dot(c, px, py);
        }
    }
    /* Same on left for RX indicator */
    for(int i = 0; i < 2; i++) {
        int offset = ((int)phase + 1 + i * 4) % 9;
        int r = 4 + offset * 2;
        if(r > 18) continue;
        for(int a = -3; a <= 3; a++) {
            int px = cx - r;
            int py = cy + a * r / 4;
            if(py >= 0 && py < 64 && px >= 0)
                canvas_draw_dot(c, px, py);
        }
    }
}

/* Progress bar x=2..125 at given y, height 4 */
static void draw_progress_bar(Canvas* c, int y, uint8_t pct) {
    canvas_draw_frame(c, 2, y, 124, 4);
    if(pct > 0) {
        uint8_t fill = (pct > 100) ? 124 : (uint8_t)((pct * 124u) / 100u);
        if(fill > 0) canvas_draw_box(c, 2, y, fill, 4);
    }
}

/* Format elapsed seconds/ms to buf */
static void fmt_elapsed(char* buf, size_t sz, uint32_t ms) {
    if(ms < 1000) snprintf(buf, sz, "%lums", (unsigned long)ms);
    else          snprintf(buf, sz, "%lu.%lus", (unsigned long)(ms / 1000), (unsigned long)((ms % 1000) / 100));
}

/* ═══════════════════════════════════════════════════════════════════
   Master draw callback
   ═══════════════════════════════════════════════════════════════════ */
static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);

    uint32_t op_ms  = 0;
    if(app->op_start_tick > 0) {
        uint32_t delta = furi_get_tick() - app->op_start_tick;
        op_ms = (uint32_t)furi_ticks_to_ms(delta);
    }

    char tmp[32];
    uint8_t phase = app->spin & 0x0F;  /* 0..15 animation phase */

    /* ── Idle / Waiting for connection ──────────────────────────── */
    if(app->ui_mode == UiIdle) {
        canvas_set_font(canvas, FontPrimary);
        draw_centred(canvas, 12, "PEN15 v3");
        draw_hline_dashed(canvas, 0, 128, 15);

        canvas_set_font(canvas, FontSecondary);
        draw_centred(canvas, 28, "Waiting for");
        draw_centred(canvas, 38, "Android app...");

        /* Pulsing dot animation */
        int dot_x = 54 + (int)(phase & 0x7) * 2;
        canvas_draw_box(canvas, dot_x, 47, 4, 4);
        canvas_draw_box(canvas, dot_x + 8, 47, 4, 4);
        canvas_draw_box(canvas, dot_x + 16, 47, 4, 4);

        canvas_draw_str(canvas, 30, 62, "[BACK] exit");

    /* ── Connected / Ready ──────────────────────────────────────── */
    } else if(app->ui_mode == UiConnected) {
        canvas_set_font(canvas, FontPrimary);
        draw_centred(canvas, 10, "PEN15 v3");
        draw_hline_dashed(canvas, 0, 128, 13);

        /* Big "READY" with spinner */
        canvas_set_font(canvas, FontBigNumbers);
        canvas_draw_str(canvas, 8, 38, "RDY");
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str(canvas, 84, 38, SPIN_CHARS[phase & 3]);

        canvas_set_font(canvas, FontSecondary);
        snprintf(tmp, sizeof(tmp), "Cmd: %s", app->cmd_disp);
        canvas_draw_str(canvas, 2, 52, tmp);
        canvas_draw_str(canvas, 2, 62, "[BACK] exit");

    /* ── Active — op in progress ─────────────────────────────────── */
    } else if(app->ui_mode == UiActive) {
        /* Header bar */
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str(canvas, 2, 10, app->op_label);
        canvas_draw_str(canvas, 100, 10, SPIN_CHARS[phase & 3]);
        draw_hline_dashed(canvas, 0, 128, 12);

        canvas_set_font(canvas, FontSecondary);

        /* ── SubGHz RX / Record / TX — show RF waves + signal count ── */
        if(app->hw_state == HwSubghzRx ||
           app->hw_state == HwSubghzRecord ||
           app->hw_state == HwSubghzTx) {

            /* Antenna icon (simple vertical line + horizontal base) */
            int acx = 22, acy = 36;
            canvas_draw_line(canvas, acx, acy - 10, acx, acy + 2);
            canvas_draw_line(canvas, acx - 4, acy + 2, acx + 4, acy + 2);

            draw_rf_waves(canvas, acx, acy - 4, phase);

            /* Signal count */
            snprintf(tmp, sizeof(tmp), "Sigs: %lu", (unsigned long)app->sig_count);
            canvas_draw_str(canvas, 55, 28, tmp);

            /* Freq detail */
            if(app->op_detail[0]) canvas_draw_str(canvas, 55, 38, app->op_detail);

            /* Elapsed */
            char el[16];
            fmt_elapsed(el, sizeof(el), op_ms);
            snprintf(tmp, sizeof(tmp), "Time: %s", el);
            canvas_draw_str(canvas, 55, 48, tmp);

            /* Progress: timeout-based, 0-95% */
            uint8_t pct = (uint8_t)((op_ms * 95) / HW_TIMEOUT_MS);
            if(pct > 95) pct = 95;
            draw_progress_bar(canvas, 56, pct);

        /* ── RFID / NFC / iKey / IR — scan rings + elapsed ────────── */
        } else {
            int cx = 20, cy = 36;
            draw_scan_rings(canvas, cx, cy, phase);

            /* Inner target circle */
            canvas_draw_circle(canvas, cx, cy, 3);

            canvas_set_font(canvas, FontSecondary);
            if(app->op_detail[0]) canvas_draw_str(canvas, 42, 26, app->op_detail);

            canvas_draw_str(canvas, 42, 38, "Scanning...");

            char el[16];
            fmt_elapsed(el, sizeof(el), op_ms);
            snprintf(tmp, sizeof(tmp), "Time: %s", el);
            canvas_draw_str(canvas, 42, 48, tmp);

            /* Timeout progress */
            uint8_t pct = (uint8_t)((op_ms * 95) / HW_TIMEOUT_MS);
            if(pct > 95) pct = 95;
            draw_progress_bar(canvas, 56, pct);
        }

        /* Remaining time indicator */
        uint32_t remain_ms = (HW_TIMEOUT_MS > op_ms) ? (HW_TIMEOUT_MS - op_ms) : 0;
        char rem[16];
        fmt_elapsed(rem, sizeof(rem), remain_ms);
        snprintf(tmp, sizeof(tmp), "~%s left", rem);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 2, 62, tmp);

    /* ── Result screen ──────────────────────────────────────────── */
    } else if(app->ui_mode == UiResult) {
        canvas_set_font(canvas, FontPrimary);

        if(app->result_ok) {
            draw_centred(canvas, 10, "[  OK  ]");
        } else {
            draw_centred(canvas, 10, "[ FAIL ]");
        }

        draw_hline_dashed(canvas, 0, 128, 13);

        /* Big checkmark or X */
        if(app->result_ok) {
            /* Simple checkmark: / then \ shape */
            canvas_draw_line(canvas, 4,  36, 10, 44);
            canvas_draw_line(canvas, 5,  36, 11, 44);
            canvas_draw_line(canvas, 10, 44, 22, 28);
            canvas_draw_line(canvas, 11, 44, 23, 28);
        } else {
            /* X shape */
            canvas_draw_line(canvas, 4, 28, 20, 44);
            canvas_draw_line(canvas, 5, 28, 21, 44);
            canvas_draw_line(canvas, 20, 28, 4, 44);
            canvas_draw_line(canvas, 21, 28, 5, 44);
        }

        canvas_set_font(canvas, FontSecondary);
        /* Show what was received */
        if(app->result_line1[0]) canvas_draw_str(canvas, 28, 32, app->result_line1);
        if(app->result_line2[0]) canvas_draw_str(canvas, 28, 44, app->result_line2);

        /* Auto-return countdown */
        uint32_t result_age = 0;
        if(app->result_show_tick > 0) {
            uint32_t tick_age = furi_get_tick() - app->result_show_tick;
            result_age = (uint32_t)furi_ticks_to_ms(tick_age);
        }
        uint32_t remaining = (2000 > result_age) ? (2000 - result_age) : 0;
        snprintf(tmp, sizeof(tmp), "Back in %lu.%lus", (unsigned long)(remaining / 1000), (unsigned long)((remaining % 1000) / 100));
        canvas_draw_str(canvas, 2, 62, tmp);

        /* Result bar: fills over 2s */
        uint8_t rpct = (uint8_t)((result_age > 2000 ? 2000 : result_age) * 100 / 2000);
        draw_progress_bar(canvas, 56, rpct);

    /* ── Bridge mode ────────────────────────────────────────────── */
    } else if(app->ui_mode == UiBridge) {
        canvas_set_font(canvas, FontPrimary);
        draw_centred(canvas, 10, "BRIDGE MODE");
        draw_hline_dashed(canvas, 0, 128, 13);

        canvas_set_font(canvas, FontSecondary);
        draw_centred(canvas, 26, "USB<->UART Active");
        draw_centred(canvas, 36, "AWOK connected");

        /* TX/RX bytes would be nice — use spin as activity indicator */
        snprintf(tmp, sizeof(tmp), "Activity: %s", SPIN_CHARS[phase & 3]);
        draw_centred(canvas, 48, tmp);

        canvas_draw_str(canvas, 2, 62, "[disconnect] exit");

    /* ── Fallback / legacy JSON mode display ─────────────────────── */
    } else {
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str(canvas, 2,  10, "PEN15 v3");
        canvas_draw_str(canvas, 80, 10, SPIN_CHARS[phase & 3]);
        canvas_draw_str(canvas, 96, 10, app->status);
        draw_hline_dashed(canvas, 0, 128, 13);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 2,  24, "CMD:");
        canvas_draw_str(canvas, 32, 24, app->cmd_disp);
        draw_progress_bar(canvas, 28, app->progress);
        canvas_draw_str(canvas, 2,  42, "RX:");
        canvas_draw_str(canvas, 24, 42, app->rx_disp);
        canvas_draw_str(canvas, 2,  62, "[BACK] exit");
    }

}

static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(ev->type == InputTypeShort && ev->key == InputKeyBack)
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop);
}

/* ═══════════════════════════════════════════════════════════════════
   JSON string unescape (in-place: \\n→\n, \\r→\r, \\t→\t, etc.)
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
   UI helpers
   ═══════════════════════════════════════════════════════════════════ */

/* Begin an active hardware operation */
static void ui_start_op(Pen15App* app, const char* label, const char* detail) {
    app->ui_mode       = UiActive;
    app->op_start_tick = furi_get_tick();
    app->sig_count     = 0;
    strncpy(app->op_label,  label,  sizeof(app->op_label)  - 1);
    strncpy(app->op_detail, detail, sizeof(app->op_detail) - 1);
    app->op_label[sizeof(app->op_label)   - 1] = '\0';
    app->op_detail[sizeof(app->op_detail) - 1] = '\0';
    app->result_line1[0] = '\0';
    app->result_line2[0] = '\0';
}

/* Show result screen with two summary lines */
static void ui_show_result(Pen15App* app, bool ok, const char* line1, const char* line2) {
    app->ui_mode          = UiResult;
    app->result_ok        = ok;
    app->result_show_tick = furi_get_tick();
    strncpy(app->result_line1, line1 ? line1 : "", sizeof(app->result_line1) - 1);
    strncpy(app->result_line2, line2 ? line2 : "", sizeof(app->result_line2) - 1);
    app->result_line1[sizeof(app->result_line1) - 1] = '\0';
    app->result_line2[sizeof(app->result_line2) - 1] = '\0';
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
        /* ibutton_key kept alive so ikey_emulate can reuse it */
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
    app->sig_count = app->subghz_rx_count;  /* live update for UI */

    if(app->hw_state == HwSubghzRecord) {
        if(app->rx_timings_count < RX_MAX_TIMES) {
            app->rx_timings[app->rx_timings_count++] = level ? (int32_t)duration : -(int32_t)duration;
        }
        if(app->rx_timings_count >= RX_MAX_TIMES) {
            app->hw_state = HwIdle;
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
            UNUSED(written);
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
            "\"fw\":\"mntm\",\"fap\":\"3.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status,  "CONN", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "ping ok", sizeof(app->rx_disp) - 1);
        app->progress = 100;
        app->ui_mode  = UiConnected;

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
            app->ui_mode  = UiBridge;
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
            "\"fw\":\"mntm\",\"fap_ver\":\"3.0\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);

    /* ── hw_stop ───────────────────────────────────────────────────── */
    } else if(strcmp(action, "hw_stop") == 0) {
        hw_stop_all(app);
        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        app->progress = 100; usb_send(app, resp);
        app->ui_mode = UiConnected;
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
        ui_start_op(app, "RFID READ", "125kHz LF");

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
        ui_start_op(app, "NFC DETECT", "13.56MHz");

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
        ui_start_op(app, "IR LEARN", "38kHz carrier");

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

        char line2[22];
        snprintf(line2, sizeof(line2), "%s cmd=%lld", proto_name, command);
        ui_show_result(app, true, "IR TX Sent", line2);

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
        ui_start_op(app, "iBUTTON READ", "1-Wire DS1990");

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

        char detail[24];
        snprintf(detail, sizeof(detail), "%lluMHz", freq / 1000000LL);
        ui_start_op(app, "RF RX", detail);

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

        char detail[24];
        snprintf(detail, sizeof(detail), "%lluMHz REC", freq / 1000000LL);
        ui_start_op(app, "RF RECORD", detail);

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

        char detail[24];
        snprintf(detail, sizeof(detail), "%lluMHz x%d", freq / 1000000LL, repeat);
        ui_start_op(app, "RF TX RAW", detail);

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
        ui_show_result(app, ok, ok ? "File Written" : "Write FAILED", path);

    /* ── storage_read ──────────────────────────────────────────────── */
    } else if(strcmp(action, "storage_read") == 0) {
        static char path[128]; memset(path, 0, sizeof(path));
        json_str(js, toks, n, "path", path, sizeof(path));

        static char content[512]; memset(content, 0, sizeof(content));
        size_t got = storage_read_file(path, content, sizeof(content));

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
        ui_start_op(app, "RFID EMULATE", type_str);

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
        ui_start_op(app, "iKEY EMULATE", "1-Wire DS1990");

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

    app->app_mode = ModeMenu;
    app->menu_index = 0;
    app->init_done = true;
    app->ui_mode   = UiIdle;

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
            /* Timeout tick: check hw deadline + result-screen auto-dismiss */
            if(app->hw_state != HwIdle &&
               furi_get_tick() > app->hw_deadline_tick) {
                static char tout[64];
                snprintf(tout, sizeof(tout),
                    "{\"status\":\"error\",\"code\":\"TIMEOUT\",\"id\":\"%s\"}\n",
                    app->hw_id);
                hw_stop_all(app);
                usb_send(app, tout);
                ui_show_result(app, false, "TIMEOUT", app->op_label);
            }

            /* Auto-dismiss result screen after 2s */
            if(app->ui_mode == UiResult && app->result_show_tick > 0) {
                uint32_t age_ticks = furi_get_tick() - app->result_show_tick;
                uint32_t age_ms = (uint32_t)furi_ticks_to_ms(age_ticks);
                if(age_ms >= 2000) {
                    app->ui_mode          = UiConnected;
                    app->result_show_tick = 0;
                }
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
            app->ui_mode = UiConnected;
            strncpy(app->status,   "WAIT",       sizeof(app->status)   - 1);
            strncpy(app->rx_disp,  "bridge off", sizeof(app->rx_disp)  - 1);
            app->progress = 0;
            view_port_update(app->vp);
        }

        if(evts & EvtHwDone) {
            /* Hardware read completed — extract result for display */
            HwState completed_state = app->hw_state;
            hw_stop_all(app);
            usb_send(app, app->hw_result_json);
            app->progress = 100;

            /* Parse result JSON for the result screen */
            char r1[22] = {0}, r2[22] = {0};
            if(completed_state == HwRfidRead) {
                /* Extract type and first 8 chars of data */
                char type_val[32] = {0}, data_val[32] = {0};
                jsmn_parser p2; jsmntok_t t2[32];
                jsmn_init(&p2);
                int nn = jsmn_parse(&p2, app->hw_result_json,
                                    strlen(app->hw_result_json), t2, 32);
                json_str(app->hw_result_json, t2, nn, "type", type_val, sizeof(type_val));
                json_str(app->hw_result_json, t2, nn, "data", data_val, sizeof(data_val));
                snprintf(r1, sizeof(r1), "RFID: %s", type_val);
                snprintf(r2, sizeof(r2), "%.16s", data_val);
                ui_show_result(app, true, r1, r2);
            } else if(completed_state == HwNfcDetect) {
                char type_val[32] = {0};
                jsmn_parser p2; jsmntok_t t2[32];
                jsmn_init(&p2);
                int nn = jsmn_parse(&p2, app->hw_result_json,
                                    strlen(app->hw_result_json), t2, 32);
                json_str(app->hw_result_json, t2, nn, "type", type_val, sizeof(type_val));
                snprintf(r1, sizeof(r1), "NFC: %s", type_val);
                strcpy(r2, "Detected!");
                ui_show_result(app, true, r1, r2);
            } else if(completed_state == HwIrRx) {
                char proto_val[24] = {0};
                jsmn_parser p2; jsmntok_t t2[32];
                jsmn_init(&p2);
                int nn = jsmn_parse(&p2, app->hw_result_json,
                                    strlen(app->hw_result_json), t2, 32);
                json_str(app->hw_result_json, t2, nn, "protocol", proto_val, sizeof(proto_val));
                snprintf(r1, sizeof(r1), "IR: %s", proto_val);
                strcpy(r2, "Signal decoded!");
                ui_show_result(app, true, r1, r2);
            } else if(completed_state == HwIkeyRead) {
                char type_val[32] = {0};
                jsmn_parser p2; jsmntok_t t2[32];
                jsmn_init(&p2);
                int nn = jsmn_parse(&p2, app->hw_result_json,
                                    strlen(app->hw_result_json), t2, 32);
                json_str(app->hw_result_json, t2, nn, "type", type_val, sizeof(type_val));
                snprintf(r1, sizeof(r1), "KEY: %s", type_val);
                strcpy(r2, "Key read OK");
                ui_show_result(app, true, r1, r2);
            } else if(completed_state == HwSubghzRx || completed_state == HwSubghzRecord) {
                snprintf(r1, sizeof(r1), "RF: %lu sigs", (unsigned long)app->sig_count);
                strcpy(r2, completed_state == HwSubghzRecord ? "Record done" : "RX done");
                ui_show_result(app, true, r1, r2);
            } else {
                ui_show_result(app, true, "Done", app->op_label);
            }
        }

        if(evts & EvtTxDone) {
            furi_hal_subghz_stop_async_tx();
            furi_hal_subghz_sleep();
            app->hw_state = HwIdle;
            static char txresp[64];
            snprintf(txresp, sizeof(txresp),
                "{\"status\":\"ok\",\"id\":\"%s\"}\n", app->hw_id);
            usb_send(app, txresp);
            app->progress = 100;
            char line1[22];
            snprintf(line1, sizeof(line1), "TX %s", app->op_detail);
            ui_show_result(app, true, line1, "Transmit done!");
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
