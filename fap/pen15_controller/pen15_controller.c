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
#define MAX_TOKENS    64
#define UART_RX_WAIT  500
#define HW_TIMEOUT_MS 30000
#define TX_MAX_TIMES  512
#define RX_MAX_TIMES  64

#define CMD_LOG_SIZE  16
#define RF_VIS_SIZE   32
#define DISP_STR_LEN  22
#define PAGE_COUNT    3

/* ── Events ───────────────────────────────────────────────────────── */
typedef enum {
    EvtStop       = (1 << 0),
    EvtUsbRx      = (1 << 1),
    EvtUartRx     = (1 << 2),
    EvtHwDone     = (1 << 3),
    EvtTxDone     = (1 << 4),
    EvtBridgeExit = (1 << 5),
    EvtInput      = (1 << 6),
} Pen15Evt;
#define ALL_EVENTS (EvtStop | EvtUsbRx | EvtUartRx | EvtHwDone | EvtTxDone | EvtBridgeExit | EvtInput)

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
typedef enum { PageDashboard, PageLog, PageRfMonitor } PageId;

typedef enum {
    CmdPending,
    CmdRunning,
    CmdOk,
    CmdError,
    CmdTimeout,
} CmdStatus;

typedef struct {
    char      action[16];
    char      id[10];
    CmdStatus status;
    uint32_t  start_tick;
    uint32_t  end_tick;
} CmdLogEntry;

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

    PinMode pin_mode[8];

    AppMode    app_mode;
    uint8_t    menu_index;
    uint32_t   bridge_exit_tick;
    bool       init_done;

    Gui*      gui;
    ViewPort* vp;

    /* ── UI state (v3) ─── */
    PageId   page;
    uint8_t  log_scroll;
    uint32_t spin_tick;

    /* ── Command log ─── */
    CmdLogEntry cmd_log[CMD_LOG_SIZE];
    uint8_t     cmd_log_head;
    uint8_t     cmd_log_count;

    /* ── Counters ─── */
    uint32_t stat_tx;
    uint32_t stat_rx;
    uint32_t stat_err;
    uint32_t stat_cmds;

    /* ── Connection state ─── */
    bool     connected;
    uint32_t last_ping_tick;
    uint32_t last_cmd_tick;

    /* ── Active operation tracking ─── */
    char     active_action[16];
    char     active_detail[32];
    uint32_t active_start_tick;
    uint8_t  active_progress;

    /* ── RF signal visualization ─── */
    uint8_t  rf_signal[RF_VIS_SIZE];
    uint8_t  rf_vis_head;
    uint32_t rf_freq_hz;

    /* ── Last result display ─── */
    char last_result[48];

    /* ── Queued input ─── */
    InputKey  queued_key;
    bool      has_queued_input;

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

/* ── OOK 650kHz CC1101 preset (FuriHalSubGhzPresetOok650Async) ────── */
static const uint8_t OOK650_PRESET[] = {
    0x02, 0x0D, 0x03, 0x07, 0x08, 0x32, 0x0B, 0x06,
    0x10, 0x17, 0x11, 0x32, 0x12, 0x30, 0x13, 0x00,
    0x14, 0x00, 0x18, 0x18, 0x19, 0x18, 0x1B, 0x07,
    0x1C, 0x00, 0x1D, 0x91, 0x20, 0xFB, 0x21, 0xB6,
    0x22, 0x11, 0x00, 0x00,
    0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

/* ═══════════════════════════════════════════════════════════════════
   Utility: ticks to seconds (integer)
   ═══════════════════════════════════════════════════════════════════ */
static uint32_t ticks_to_sec(uint32_t start) {
    uint32_t now = furi_get_tick();
    if(now < start) return 0;
    return (now - start) / furi_ms_to_ticks(1000);
}

/* ═══════════════════════════════════════════════════════════════════
   Command log helpers
   ═══════════════════════════════════════════════════════════════════ */
static void cmd_log_push(Pen15App* app, const char* action, const char* id) {
    CmdLogEntry* e = &app->cmd_log[app->cmd_log_head];
    strncpy(e->action, action, sizeof(e->action) - 1);
    e->action[sizeof(e->action) - 1] = '\0';
    strncpy(e->id, id, sizeof(e->id) - 1);
    e->id[sizeof(e->id) - 1] = '\0';
    e->status     = CmdPending;
    e->start_tick = furi_get_tick();
    e->end_tick   = 0;

    app->cmd_log_head = (app->cmd_log_head + 1) % CMD_LOG_SIZE;
    if(app->cmd_log_count < CMD_LOG_SIZE) app->cmd_log_count++;
    app->stat_cmds++;
}

static CmdLogEntry* cmd_log_find(Pen15App* app, const char* id) {
    for(uint8_t i = 0; i < app->cmd_log_count; i++) {
        int idx = ((int)app->cmd_log_head - 1 - (int)i + CMD_LOG_SIZE * 2) % CMD_LOG_SIZE;
        if(strcmp(app->cmd_log[idx].id, id) == 0) return &app->cmd_log[idx];
    }
    return NULL;
}

static void cmd_log_set_status(Pen15App* app, const char* id, CmdStatus status) {
    CmdLogEntry* e = cmd_log_find(app, id);
    if(e) {
        e->status   = status;
        e->end_tick = furi_get_tick();
    }
}

static CmdLogEntry* cmd_log_get(Pen15App* app, uint8_t reverse_idx) {
    if(reverse_idx >= app->cmd_log_count) return NULL;
    int idx = ((int)app->cmd_log_head - 1 - (int)reverse_idx + CMD_LOG_SIZE * 2) % CMD_LOG_SIZE;
    return &app->cmd_log[idx];
}

/* ═══════════════════════════════════════════════════════════════════
   RF visualization helpers
   ═══════════════════════════════════════════════════════════════════ */
static void rf_vis_push(Pen15App* app, uint8_t level) {
    if(level > 8) level = 8;
    app->rf_signal[app->rf_vis_head] = level;
    app->rf_vis_head = (app->rf_vis_head + 1) % RF_VIS_SIZE;
}

/* ═══════════════════════════════════════════════════════════════════
   Hardware state name
   ═══════════════════════════════════════════════════════════════════ */
static const char* hw_state_name(HwState s) {
    switch(s) {
    case HwIdle:         return "IDLE";
    case HwRfidRead:     return "RFID READ";
    case HwRfidEmulate:  return "RFID EMUL";
    case HwNfcDetect:    return "NFC SCAN";
    case HwIrRx:         return "IR LEARN";
    case HwIkeyRead:     return "iKEY READ";
    case HwIkeyEmulate:  return "iKEY EMUL";
    case HwSubghzRx:     return "RF RX";
    case HwSubghzRecord: return "RF RECORD";
    case HwSubghzTx:     return "RF TX";
    }
    return "?";
}

static const char* cmd_status_icon(CmdStatus s) {
    switch(s) {
    case CmdPending:  return "..";
    case CmdRunning:  return ">>";
    case CmdOk:       return "OK";
    case CmdError:    return "!!";
    case CmdTimeout:  return "TO";
    }
    return "??";
}

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
static void cdc_state_cb(void* ctx, uint8_t s) { UNUSED(ctx); UNUSED(s); }
static void cdc_ctrl_cb(void* ctx, uint8_t s) {
    Pen15App* app = ctx;
    if(app->bridge_mode && !(s & 0x01)) {
        app->app_mode   = ModeJson;
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
    app->stat_tx++;
}

static void usb_send_raw(Pen15App* app, const uint8_t* data, uint16_t len) {
    if(len == 0) return;
    furi_semaphore_acquire(app->tx_sem, 300);
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    furi_hal_cdc_send(0, (uint8_t*)data, len);
    furi_mutex_release(app->usb_mtx);
}

/* Send ACK immediately when a command is received and parsed */
static void usb_send_ack(Pen15App* app, const char* action, const char* id) {
    static char ack[128];
    snprintf(ack, sizeof(ack),
        "{\"status\":\"ack\",\"action\":\"%s\",\"id\":\"%s\"}\n", action, id);
    usb_send(app, ack);
}

/* Send progress update for long-running operations */
static void usb_send_progress(Pen15App* app, const char* id, uint8_t pct, const char* detail) {
    static char prog[192];
    snprintf(prog, sizeof(prog),
        "{\"status\":\"progress\",\"percent\":%u,\"detail\":\"%s\",\"id\":\"%s\"}\n",
        (unsigned)pct, detail, id);
    usb_send(app, prog);
}

/* ═══════════════════════════════════════════════════════════════════
   GUI — Page: Dashboard
   ═══════════════════════════════════════════════════════════════════ */
static void draw_dashboard(Canvas* canvas, Pen15App* app) {
    static char buf[48];
    uint32_t anim = app->spin_tick;

    /* Header bar */
    canvas_draw_box(canvas, 0, 0, 128, 11);
    canvas_set_color(canvas, ColorWhite);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "PEN15 v3");

    /* Connection indicator: filled circle if connected, hollow if not */
    if(app->connected) {
        canvas_draw_disc(canvas, 108, 5, 3);
        canvas_draw_str_aligned(canvas, 114, 9, AlignLeft, AlignBottom, "ON");
    } else {
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_disc(canvas, 108, 5, 3);
        canvas_set_color(canvas, ColorBlack);
        /* Blink the dot when disconnected */
        if(anim & 1) {
            canvas_draw_circle(canvas, 108, 5, 3);
        }
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_str_aligned(canvas, 114, 9, AlignLeft, AlignBottom, "--");
    }

    canvas_set_color(canvas, ColorBlack);

    /* Active operation section */
    if(app->hw_state != HwIdle) {
        const char* hw_name = hw_state_name(app->hw_state);
        uint32_t elapsed = ticks_to_sec(app->active_start_tick);

        /* Animated arrow */
        const char* arrows[] = {">  ", ">> ", ">>>"};
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 2, 21, arrows[anim % 3]);
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str(canvas, 16, 21, hw_name);

        /* Elapsed time */
        snprintf(buf, sizeof(buf), "%lus", (unsigned long)elapsed);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str_aligned(canvas, 126, 21, AlignRight, AlignBottom, buf);

        /* Detail line */
        canvas_draw_str(canvas, 4, 30, app->active_detail);

        /* Progress bar */
        canvas_draw_frame(canvas, 2, 32, 124, 7);
        uint8_t fill_pct = app->active_progress;
        if(fill_pct > 100) fill_pct = 100;
        uint8_t fill_w = (uint8_t)((fill_pct * 122) / 100);
        if(fill_w > 0) canvas_draw_box(canvas, 3, 33, fill_w, 5);

        /* Percent text inside bar */
        snprintf(buf, sizeof(buf), "%u%%", fill_pct);
        canvas_set_font(canvas, FontKeyboard);
        uint8_t pct_x = (uint8_t)(fill_w > 20 ? (3 + fill_w / 2 - 6) : (fill_w + 5));
        if(pct_x > 110) pct_x = 110;
        canvas_draw_str(canvas, pct_x, 38, buf);
    } else {
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 2, 21, "No active operation");
        canvas_draw_str(canvas, 2, 30, "Waiting for commands...");

        /* Divider */
        canvas_draw_line(canvas, 0, 32, 128, 32);
    }

    /* Separator */
    canvas_draw_line(canvas, 0, 40, 128, 40);

    /* Last result */
    canvas_set_font(canvas, FontSecondary);
    if(app->last_result[0]) {
        canvas_draw_str(canvas, 2, 49, app->last_result);
    } else {
        canvas_draw_str(canvas, 2, 49, "No results yet");
    }

    /* Stats bar */
    snprintf(buf, sizeof(buf), "TX:%lu RX:%lu ERR:%lu CMD:%lu",
        (unsigned long)app->stat_tx, (unsigned long)app->stat_rx,
        (unsigned long)app->stat_err, (unsigned long)app->stat_cmds);
    canvas_set_font(canvas, FontKeyboard);
    canvas_draw_str(canvas, 2, 57, buf);

    /* Navigation footer */
    canvas_draw_line(canvas, 0, 58, 128, 58);
    canvas_set_font(canvas, FontKeyboard);
    canvas_draw_str(canvas, 2, 63, "[<]");
    canvas_draw_str(canvas, 36, 63, "Dashboard");
    canvas_draw_str(canvas, 100, 63, "Log[>]");
}

/* ═══════════════════════════════════════════════════════════════════
   GUI — Page: Command Log
   ═══════════════════════════════════════════════════════════════════ */
static void draw_log(Canvas* canvas, Pen15App* app) {
    static char buf[48];

    /* Header */
    canvas_draw_box(canvas, 0, 0, 128, 11);
    canvas_set_color(canvas, ColorWhite);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "CMD LOG");
    snprintf(buf, sizeof(buf), "%u cmds", (unsigned)app->cmd_log_count);
    canvas_draw_str_aligned(canvas, 126, 9, AlignRight, AlignBottom, buf);
    canvas_set_color(canvas, ColorBlack);

    /* Log entries — show up to 5 at a time */
    canvas_set_font(canvas, FontKeyboard);
    uint8_t visible = 5;
    for(uint8_t i = 0; i < visible; i++) {
        uint8_t entry_idx = app->log_scroll + i;
        CmdLogEntry* e = cmd_log_get(app, entry_idx);
        if(!e) break;

        uint8_t y = 19 + i * 9;
        const char* icon = cmd_status_icon(e->status);

        /* Status icon with visual distinction */
        if(e->status == CmdOk) {
            canvas_draw_box(canvas, 1, y - 6, 11, 7);
            canvas_set_color(canvas, ColorWhite);
            canvas_draw_str(canvas, 2, y, icon);
            canvas_set_color(canvas, ColorBlack);
        } else if(e->status == CmdError || e->status == CmdTimeout) {
            canvas_draw_frame(canvas, 1, y - 6, 11, 7);
            canvas_draw_str(canvas, 2, y, icon);
        } else {
            canvas_draw_str(canvas, 2, y, icon);
        }

        canvas_draw_str(canvas, 15, y, e->action);

        /* Duration */
        uint32_t dur_tick = e->end_tick ? e->end_tick : furi_get_tick();
        uint32_t dur_ms = (dur_tick - e->start_tick) * 1000 / furi_ms_to_ticks(1000);
        if(dur_ms < 10000) {
            snprintf(buf, sizeof(buf), "%lu.%lus",
                (unsigned long)(dur_ms / 1000), (unsigned long)((dur_ms % 1000) / 100));
        } else {
            snprintf(buf, sizeof(buf), "%lus", (unsigned long)(dur_ms / 1000));
        }
        canvas_draw_str_aligned(canvas, 126, y, AlignRight, AlignBottom, buf);
    }

    if(app->cmd_log_count == 0) {
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str(canvas, 20, 35, "No commands yet");
    }

    /* Scroll indicator */
    if(app->cmd_log_count > visible) {
        canvas_set_font(canvas, FontKeyboard);
        if(app->log_scroll > 0) canvas_draw_str(canvas, 60, 12, "^");
        if(app->log_scroll + visible < app->cmd_log_count) canvas_draw_str(canvas, 60, 63, "v");
    }

    /* Navigation footer */
    canvas_draw_line(canvas, 0, 58, 128, 58);
    canvas_set_font(canvas, FontKeyboard);
    canvas_draw_str(canvas, 2, 63, "[<]Dash");
    canvas_draw_str(canvas, 50, 63, "Log");
    canvas_draw_str(canvas, 92, 63, "RF[>]");
}

/* ═══════════════════════════════════════════════════════════════════
   GUI — Page: RF Monitor
   ═══════════════════════════════════════════════════════════════════ */
static void draw_rf_monitor(Canvas* canvas, Pen15App* app) {
    static char buf[48];

    /* Header */
    canvas_draw_box(canvas, 0, 0, 128, 11);
    canvas_set_color(canvas, ColorWhite);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "RF MON");

    if(app->rf_freq_hz > 0) {
        uint32_t mhz = app->rf_freq_hz / 1000000;
        uint32_t khz = (app->rf_freq_hz % 1000000) / 1000;
        snprintf(buf, sizeof(buf), "%lu.%03luMHz", (unsigned long)mhz, (unsigned long)khz);
        canvas_draw_str_aligned(canvas, 126, 9, AlignRight, AlignBottom, buf);
    }
    canvas_set_color(canvas, ColorBlack);

    /* Signal activity visualization — bar graph */
    uint8_t bar_x = 2;
    uint8_t bar_bottom = 36;
    uint8_t bar_max_h = 20;
    uint8_t bar_w = 3;
    uint8_t gap = 1;

    for(uint8_t i = 0; i < RF_VIS_SIZE; i++) {
        uint8_t idx = (app->rf_vis_head + i) % RF_VIS_SIZE;
        uint8_t level = app->rf_signal[idx];
        uint8_t h = (uint8_t)((level * bar_max_h) / 8);
        if(h > 0) {
            canvas_draw_box(canvas, bar_x, bar_bottom - h, bar_w, h);
        }
        canvas_draw_dot(canvas, bar_x + 1, bar_bottom);
        bar_x += bar_w + gap;
        if(bar_x + bar_w > 126) break;
    }

    /* Baseline */
    canvas_draw_line(canvas, 2, bar_bottom + 1, 126, bar_bottom + 1);

    /* Stats below the graph */
    canvas_set_font(canvas, FontSecondary);

    bool rf_active = (app->hw_state == HwSubghzRx ||
                      app->hw_state == HwSubghzRecord ||
                      app->hw_state == HwSubghzTx);

    if(rf_active) {
        uint32_t elapsed = ticks_to_sec(app->active_start_tick);
        snprintf(buf, sizeof(buf), "Pulses: %lu  Time: %lus",
            (unsigned long)app->subghz_rx_count, (unsigned long)elapsed);
        canvas_draw_str(canvas, 2, 46, buf);

        if(app->hw_state == HwSubghzRecord) {
            snprintf(buf, sizeof(buf), "Recording: %zu/%d samples",
                app->rx_timings_count, RX_MAX_TIMES);
            canvas_draw_str(canvas, 2, 55, buf);

            /* Recording progress bar */
            uint8_t rec_pct = (uint8_t)((app->rx_timings_count * 100) / RX_MAX_TIMES);
            canvas_draw_frame(canvas, 2, 46, 80, 5);
            uint8_t rec_w = (uint8_t)((rec_pct * 78) / 100);
            if(rec_w > 0) canvas_draw_box(canvas, 3, 47, rec_w, 3);
        } else if(app->hw_state == HwSubghzTx) {
            snprintf(buf, sizeof(buf), "TX rep %d/%d", app->tx_repeat_cnt, app->tx_repeat);
            canvas_draw_str(canvas, 2, 55, buf);
        } else {
            const char* state = hw_state_name(app->hw_state);
            snprintf(buf, sizeof(buf), "Mode: %s", state);
            canvas_draw_str(canvas, 2, 55, buf);
        }
    } else {
        canvas_draw_str(canvas, 10, 46, "No RF operation active");
        canvas_draw_str(canvas, 10, 55, "Start scan from phone");
    }

    /* Navigation footer */
    canvas_draw_line(canvas, 0, 58, 128, 58);
    canvas_set_font(canvas, FontKeyboard);
    canvas_draw_str(canvas, 2, 63, "[<]Log");
    canvas_draw_str(canvas, 46, 63, "RF Mon");
    if(rf_active) {
        canvas_draw_str(canvas, 90, 63, "[OK]Stop");
    }
}

/* ═══════════════════════════════════════════════════════════════════
   GUI — Bridge mode overlay
   ═══════════════════════════════════════════════════════════════════ */
static void draw_bridge(Canvas* canvas, Pen15App* app) {
    static char buf[32];
    uint32_t anim = app->spin_tick;

    canvas_draw_box(canvas, 0, 0, 128, 11);
    canvas_set_color(canvas, ColorWhite);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "UART BRIDGE");
    canvas_set_color(canvas, ColorBlack);

    /* Animated data flow arrows */
    canvas_set_font(canvas, FontSecondary);
    const char* flow[] = {"Phone <-> AWOK", "Phone  <-> AWOK", "Phone   <-> AWOK"};
    canvas_draw_str(canvas, 10, 30, flow[anim % 3]);

    snprintf(buf, sizeof(buf), "TX:%lu RX:%lu",
        (unsigned long)app->stat_tx, (unsigned long)app->stat_rx);
    canvas_draw_str(canvas, 20, 45, buf);

    canvas_set_font(canvas, FontKeyboard);
    canvas_draw_str(canvas, 20, 62, "DTR drop = exit bridge");
}

/* ═══════════════════════════════════════════════════════════════════
   GUI main draw callback
   ═══════════════════════════════════════════════════════════════════ */
static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);

    if(app->app_mode == ModeBridge) {
        draw_bridge(canvas, app);
        return;
    }

    switch(app->page) {
    case PageDashboard:  draw_dashboard(canvas, app); break;
    case PageLog:        draw_log(canvas, app);       break;
    case PageRfMonitor:  draw_rf_monitor(canvas, app); break;
    }
}

/* ═══════════════════════════════════════════════════════════════════
   Input callback — queue key for main loop
   ═══════════════════════════════════════════════════════════════════ */
static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(ev->type != InputTypeShort) return;

    if(ev->key == InputKeyBack) {
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop);
        return;
    }

    app->queued_key = ev->key;
    app->has_queued_input = true;
    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtInput);
}

/* ═══════════════════════════════════════════════════════════════════
   Handle navigation input
   ═══════════════════════════════════════════════════════════════════ */
static void handle_input(Pen15App* app) {
    if(!app->has_queued_input) return;
    app->has_queued_input = false;

    InputKey key = app->queued_key;

    switch(key) {
    case InputKeyRight:
        if(app->page < PAGE_COUNT - 1) app->page++;
        break;
    case InputKeyLeft:
        if(app->page > 0) app->page--;
        break;
    case InputKeyUp:
        if(app->page == PageLog && app->log_scroll > 0) app->log_scroll--;
        break;
    case InputKeyDown:
        if(app->page == PageLog && app->log_scroll + 5 < app->cmd_log_count) app->log_scroll++;
        break;
    case InputKeyOk:
        if(app->page == PageRfMonitor &&
           (app->hw_state == HwSubghzRx || app->hw_state == HwSubghzRecord || app->hw_state == HwSubghzTx)) {
            /* Stop current RF operation via hw_stop */
            static char stop_resp[64];
            snprintf(stop_resp, sizeof(stop_resp),
                "{\"status\":\"ok\",\"action\":\"hw_stop\",\"id\":\"%s\"}\n", app->hw_id);
            /* Will be stopped in main loop since we set hw_state directly is unsafe;
               use the same mechanism as JSON command */
            app->hw_deadline_tick = 0;
        }
        break;
    default:
        break;
    }
}

/* ═══════════════════════════════════════════════════════════════════
   JSON string unescape (in-place)
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
    app->hw_state        = HwIdle;
    app->active_action[0] = '\0';
    app->active_detail[0] = '\0';
    app->active_progress  = 0;
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

        snprintf(app->last_result, sizeof(app->last_result),
            "RFID: %s %s", name ? name : "?", hex);

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

        snprintf(app->last_result, sizeof(app->last_result),
            "IR: %s A:%lu C:%lu",
            infrared_get_protocol_name(msg->protocol),
            (unsigned long)msg->address, (unsigned long)msg->command);

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

    snprintf(app->last_result, sizeof(app->last_result),
        "iKey: %s", name ? name : "?");

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

        snprintf(app->last_result, sizeof(app->last_result),
            "NFC: %s detected", proto_name);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* SubGHz RX pair callback */
static void subghz_rx_pair_cb(void* ctx, bool level, uint32_t duration) {
    Pen15App* app = ctx;
    app->subghz_rx_count++;

    /* Feed RF visualization: map duration to a 0-8 level */
    uint8_t vis_level = (uint8_t)(duration / 100);
    if(vis_level > 8) vis_level = 8;
    if(vis_level < 1 && level) vis_level = 1;
    rf_vis_push(app, vis_level);

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
            snprintf(p, (size_t)remaining, "\",\"id\":\"%s\"}\n", app->hw_id);

            snprintf(app->last_result, sizeof(app->last_result),
                "RF REC: %zu samples captured", app->rx_timings_count);

            furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
        }
        return;
    }

    if(app->subghz_rx_count >= 50 && app->hw_state == HwSubghzRx) {
        app->hw_state = HwIdle;
        snprintf(app->hw_result_json, sizeof(app->hw_result_json),
            "{\"status\":\"ok\",\"count\":%u,\"timings\":\"\",\"id\":\"%s\"}\n",
            (unsigned)app->subghz_rx_count, app->hw_id);

        snprintf(app->last_result, sizeof(app->last_result),
            "RF RX: %u pulses detected", (unsigned)app->subghz_rx_count);

        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtHwDone);
    }
}

/* SubGHz TX ISR callback */
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
   Parse comma-separated timing string
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
   Helper: set active operation display
   ═══════════════════════════════════════════════════════════════════ */
static void set_active_op(Pen15App* app, const char* action, const char* detail) {
    strncpy(app->active_action, action, sizeof(app->active_action) - 1);
    app->active_action[sizeof(app->active_action) - 1] = '\0';
    strncpy(app->active_detail, detail, sizeof(app->active_detail) - 1);
    app->active_detail[sizeof(app->active_detail) - 1] = '\0';
    app->active_start_tick = furi_get_tick();
    app->active_progress   = 0;
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
        app->stat_err++;
        return;
    }

    json_str(js, toks, n, "action", action, sizeof(action));
    json_str(js, toks, n, "id",     id,     sizeof(id));

    app->stat_rx++;
    app->last_cmd_tick = furi_get_tick();

    /* Log the command and send ACK */
    cmd_log_push(app, action, id);
    usb_send_ack(app, action, id);

    view_port_update(app->vp);

    /* ── ping ──────────────────────────────────────────────────────── */
    if(strcmp(action, "ping") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap\":\"3.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdOk);
        app->connected      = true;
        app->last_ping_tick = furi_get_tick();
        snprintf(app->last_result, sizeof(app->last_result), "ping OK - connected");

    /* ── gpio_mode ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_mode") == 0) {
        int  pin  = json_int(js, toks, n, "pin", -1);
        char mode[16] = {0};
        json_str(js, toks, n, "mode", mode, sizeof(mode));
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
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
            cmd_log_set_status(app, id, CmdOk);
            snprintf(app->last_result, sizeof(app->last_result), "GPIO P%d -> %s", pin, mode);
        }
        usb_send(app, resp);

    /* ── gpio_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_write") == 0) {
        int pin   = json_int(js, toks, n, "pin",   -1);
        int value = json_int(js, toks, n, "value", -1);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
        } else if(app->pin_mode[pin] != PinOutput) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"NOT_OUTPUT\","
                "\"message\":\"Call gpio_mode output first\",\"id\":\"%s\"}\n", id);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
        } else {
            furi_hal_gpio_write(EXT_PINS[pin], value != 0);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, value != 0 ? 1 : 0, id);
            cmd_log_set_status(app, id, CmdOk);
            snprintf(app->last_result, sizeof(app->last_result), "GPIO P%d = %d", pin, value != 0 ? 1 : 0);
        }
        usb_send(app, resp);

    /* ── gpio_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_read") == 0) {
        int pin = json_int(js, toks, n, "pin", -1);
        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
        } else {
            if(app->pin_mode[pin] == PinUnset) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput, GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            bool val = furi_hal_gpio_read(EXT_PINS[pin]);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, val ? 1 : 0, id);
            cmd_log_set_status(app, id, CmdOk);
            snprintf(app->last_result, sizeof(app->last_result), "GPIO P%d read = %d", pin, val ? 1 : 0);
        }
        usb_send(app, resp);

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
        usb_send(app, resp);
        if(uart_ok) {
            cmd_log_set_status(app, id, CmdOk);
            app->app_mode = ModeBridge;
            app->bridge_exit_tick = 0;
            snprintf(app->last_result, sizeof(app->last_result), "UART %d baud - bridge on", baud);
        } else {
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
        }

    /* ── uart_send ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_send") == 0) {
        static char data[128]; memset(data, 0, sizeof(data));
        json_str(js, toks, n, "data", data, sizeof(data));
        if(!app->uart_ready) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"UART_NOT_INIT\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
        } else {
            cmd_log_set_status(app, id, CmdRunning);
            set_active_op(app, "uart_send", "TX+wait RX...");
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
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdOk);
            snprintf(app->last_result, sizeof(app->last_result),
                "UART: %s", awok_len > 0 ? awok : "(no rx)");
            app->active_action[0] = '\0';
        }

    /* ── get_device_info ───────────────────────────────────────────── */
    } else if(strcmp(action, "get_device_info") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap_ver\":\"3.0\","
            "\"hw_state\":\"%s\",\"cmds\":%lu,"
            "\"tx\":%lu,\"rx\":%lu,\"err\":%lu,\"id\":\"%s\"}\n",
            hw_state_name(app->hw_state),
            (unsigned long)app->stat_cmds,
            (unsigned long)app->stat_tx,
            (unsigned long)app->stat_rx,
            (unsigned long)app->stat_err,
            id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdOk);

    /* ── hw_stop ───────────────────────────────────────────────────── */
    } else if(strcmp(action, "hw_stop") == 0) {
        hw_stop_all(app);
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"action\":\"hw_stop\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdOk);
        snprintf(app->last_result, sizeof(app->last_result), "HW stopped");

    /* ── rfid_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "rfid_read") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->rfid_dict   = protocol_dict_alloc(lfrfid_protocols, LFRFIDProtocolMax);
        app->rfid_worker = lfrfid_worker_alloc(app->rfid_dict);
        lfrfid_worker_read_start(app->rfid_worker, LFRFIDWorkerReadTypeAuto, rfid_cb, app);
        app->hw_state = HwRfidRead;

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "rfid_read", "Hold tag near Flipper");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"reading\",\"hw\":\"rfid\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);

    /* ── nfc_detect ────────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_detect") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->nfc         = nfc_alloc();
        app->nfc_scanner = nfc_scanner_alloc(app->nfc);
        nfc_scanner_start(app->nfc_scanner, nfc_scanner_cb, app);
        app->hw_state = HwNfcDetect;

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "nfc_detect", "Hold NFC tag to back");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"scanning\",\"hw\":\"nfc\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);

    /* ── ir_rx ─────────────────────────────────────────────────────── */
    } else if(strcmp(action, "ir_rx") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);

        app->ir_worker = infrared_worker_alloc();
        infrared_worker_rx_enable_signal_decoding(app->ir_worker, true);
        infrared_worker_rx_set_received_signal_callback(app->ir_worker, ir_rx_cb, app);
        infrared_worker_rx_start(app->ir_worker);
        app->hw_state = HwIrRx;

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "ir_rx", "Point remote at Flipper");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"reading\",\"hw\":\"ir\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);

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

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "ir_tx", "Transmitting IR...");
        view_port_update(app->vp);

        InfraredWorker* tx_worker = infrared_worker_alloc();
        infrared_worker_set_decoded_signal(tx_worker, &msg);
        infrared_worker_tx_set_get_signal_callback(
            tx_worker, infrared_worker_tx_get_signal_steady_callback, tx_worker);
        infrared_worker_tx_start(tx_worker);
        furi_delay_ms(200);
        infrared_worker_tx_stop(tx_worker);
        infrared_worker_free(tx_worker);

        snprintf(resp, sizeof(resp), "{\"status\":\"ok\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdOk);
        snprintf(app->last_result, sizeof(app->last_result),
            "IR TX: %s A:%lu C:%lu", proto_name, (unsigned long)address, (unsigned long)command);
        app->active_action[0] = '\0';

    /* ── ikey_read ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "ikey_read") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
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

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "ikey_read", "Touch iButton probe");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"reading\",\"hw\":\"ibutton\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);

    /* ── subghz_rx ─────────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_rx") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick  = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);
        app->subghz_rx_count   = 0;
        app->rf_freq_hz        = (uint32_t)freq;

        /* Clear visualization */
        memset(app->rf_signal, 0, sizeof(app->rf_signal));
        app->rf_vis_head = 0;

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_rx();

        app->subghz_worker = subghz_worker_alloc();
        subghz_worker_set_pair_callback(app->subghz_worker, subghz_rx_pair_cb);
        subghz_worker_set_context(app->subghz_worker, app);
        subghz_worker_start(app->subghz_worker);
        app->hw_state = HwSubghzRx;

        cmd_log_set_status(app, id, CmdRunning);
        {
            char freq_str[24];
            snprintf(freq_str, sizeof(freq_str), "RX %lu.%03lu MHz",
                (unsigned long)(freq / 1000000), (unsigned long)((freq % 1000000) / 1000));
            set_active_op(app, "subghz_rx", freq_str);
        }

        /* Auto-switch to RF monitor page */
        app->page = PageRfMonitor;

        snprintf(resp, sizeof(resp),
            "{\"status\":\"scanning\",\"hw\":\"subghz\",\"freq\":%lld,\"id\":\"%s\"}\n", freq, id);
        usb_send(app, resp);

    /* ── subghz_record ────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_record") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        app->hw_deadline_tick   = furi_get_tick() + furi_ms_to_ticks(HW_TIMEOUT_MS);
        app->subghz_rx_count    = 0;
        app->rx_timings_count   = 0;
        app->subghz_record_mode = true;
        app->rf_freq_hz         = (uint32_t)freq;

        memset(app->rf_signal, 0, sizeof(app->rf_signal));
        app->rf_vis_head = 0;

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_rx();

        app->subghz_worker = subghz_worker_alloc();
        subghz_worker_set_pair_callback(app->subghz_worker, subghz_rx_pair_cb);
        subghz_worker_set_context(app->subghz_worker, app);
        subghz_worker_start(app->subghz_worker);
        app->hw_state = HwSubghzRecord;

        cmd_log_set_status(app, id, CmdRunning);
        {
            char freq_str[24];
            snprintf(freq_str, sizeof(freq_str), "REC %lu.%03lu MHz",
                (unsigned long)(freq / 1000000), (unsigned long)((freq % 1000000) / 1000));
            set_active_op(app, "subghz_rec", freq_str);
        }

        app->page = PageRfMonitor;

        snprintf(resp, sizeof(resp),
            "{\"status\":\"recording\",\"hw\":\"subghz\",\"freq\":%lld,\"id\":\"%s\"}\n", freq, id);
        usb_send(app, resp);

    /* ── subghz_tx_raw ─────────────────────────────────────────────── */
    } else if(strcmp(action, "subghz_tx_raw") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        long long freq = json_ll(js, toks, n, "freq", 433920000LL);
        int repeat     = json_int(js, toks, n, "repeat", 3);

        static char timings_str[1024]; memset(timings_str, 0, sizeof(timings_str));
        json_str(js, toks, n, "timings", timings_str, sizeof(timings_str));

        app->tx_count      = parse_timings(timings_str, app->tx_timings, TX_MAX_TIMES);
        app->tx_idx        = 0;
        app->tx_repeat     = repeat;
        app->tx_repeat_cnt = 0;
        app->rf_freq_hz    = (uint32_t)freq;
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);

        if(app->tx_count == 0) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_TIMINGS\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }

        furi_hal_subghz_reset();
        furi_hal_subghz_load_custom_preset(OOK650_PRESET);
        furi_hal_subghz_set_frequency_and_path((uint32_t)freq);
        furi_hal_subghz_start_async_tx(subghz_tx_isr, app);
        app->hw_state = HwSubghzTx;

        cmd_log_set_status(app, id, CmdRunning);
        {
            char freq_str[24];
            snprintf(freq_str, sizeof(freq_str), "TX %lu.%03lu x%d",
                (unsigned long)(freq / 1000000), (unsigned long)((freq % 1000000) / 1000), repeat);
            set_active_op(app, "subghz_tx", freq_str);
        }

        app->page = PageRfMonitor;

        snprintf(resp, sizeof(resp),
            "{\"status\":\"transmitting\",\"hw\":\"subghz\","
            "\"freq\":%lld,\"count\":%zu,\"repeat\":%d,\"id\":\"%s\"}\n",
            freq, app->tx_count, repeat, id);
        usb_send(app, resp);

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
        usb_send(app, resp);
        cmd_log_set_status(app, id, ok ? CmdOk : CmdError);
        if(!ok) app->stat_err++;
        snprintf(app->last_result, sizeof(app->last_result),
            "Write %s: %s", path, ok ? "OK" : "FAIL");

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
        usb_send(app, resp);
        cmd_log_set_status(app, id, got > 0 ? CmdOk : CmdError);
        if(got == 0) app->stat_err++;
        snprintf(app->last_result, sizeof(app->last_result),
            "Read %s: %zu bytes", path, got);

    /* ── rfid_emulate ─────────────────────────────────────────────── */
    } else if(strcmp(action, "rfid_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
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

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "rfid_emul", "Emulating RFID tag");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"hw\":\"rfid_emul\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        snprintf(app->last_result, sizeof(app->last_result),
            "RFID emul: %s", type_str);

    /* ── ikey_emulate ─────────────────────────────────────────────── */
    } else if(strcmp(action, "ikey_emulate") == 0) {
        if(app->hw_state != HwIdle) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"HW_BUSY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        if(!app->ibutton_key) {
            snprintf(resp, sizeof(resp), "{\"status\":\"error\",\"code\":\"NO_KEY\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
            cmd_log_set_status(app, id, CmdError);
            app->stat_err++;
            return;
        }
        app->ibutton_protocols = ibutton_protocols_alloc();
        app->ibutton_worker    = ibutton_worker_alloc(app->ibutton_protocols);
        strncpy(app->hw_id, id, sizeof(app->hw_id) - 1);
        ibutton_worker_emulate_start(app->ibutton_worker, app->ibutton_key);
        app->hw_state = HwIkeyEmulate;

        cmd_log_set_status(app, id, CmdRunning);
        set_active_op(app, "ikey_emul", "Emulating iButton");

        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"hw\":\"ikey_emul\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        snprintf(app->last_result, sizeof(app->last_result), "iKey emulating");

    /* ── nfc_emulate ──────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_emulate") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_emulate requires NFC app\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdError);
        app->stat_err++;

    /* ── nfc_write ────────────────────────────────────────────────── */
    } else if(strcmp(action, "nfc_write") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"NOT_SUPPORTED\","
            "\"message\":\"nfc_write requires NFC app\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdError);
        app->stat_err++;

    /* ── unknown ───────────────────────────────────────────────────── */
    } else {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"UNKNOWN\","
            "\"message\":\"%s\",\"id\":\"%s\"}\n", action, id);
        usb_send(app, resp);
        cmd_log_set_status(app, id, CmdError);
        app->stat_err++;
        snprintf(app->last_result, sizeof(app->last_result), "Unknown: %s", action);
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

    app->app_mode  = ModeJson;
    app->page      = PageDashboard;
    app->init_done = true;

    app->thread      = furi_thread_get_current();
    app->usb_mtx     = furi_mutex_alloc(FuriMutexTypeNormal);
    app->tx_sem      = furi_semaphore_alloc(1, 1);
    app->uart_rx_buf = furi_stream_buffer_alloc(UART_RX_BUF, 1);
    app->hw_state    = HwIdle;

    app->vp  = view_port_alloc();
    app->gui = furi_record_open(RECORD_GUI);
    view_port_draw_callback_set(app->vp,  draw_cb,  app);
    view_port_input_callback_set(app->vp, input_cb, app);
    gui_add_view_port(app->gui, app->vp, GuiLayerFullscreen);

    app->cli_vcp = furi_record_open(RECORD_CLI_VCP);
    cli_vcp_disable(app->cli_vcp);
    furi_hal_cdc_set_callbacks(0, (CdcCallbacks*)&CDC_CB, app);

    /* Send startup beacon so Android can detect FAP is running */
    usb_send(app, "{\"status\":\"ready\",\"fap\":\"3.0\",\"device\":\"flipper_zero\"}\n");

    /* Main event loop */
    while(true) {
        uint32_t evts = furi_thread_flags_wait(ALL_EVENTS, FuriFlagWaitAny, 200);

        if(evts & FuriFlagError) {
            /* Periodic tick — update animations, check timeouts */
            app->spin_tick++;

            /* Check hardware operation timeout */
            if(app->hw_state != HwIdle &&
               furi_get_tick() > app->hw_deadline_tick) {
                static char tout[128];
                snprintf(tout, sizeof(tout),
                    "{\"status\":\"error\",\"code\":\"TIMEOUT\","
                    "\"hw\":\"%s\",\"elapsed\":%lu,\"id\":\"%s\"}\n",
                    hw_state_name(app->hw_state),
                    (unsigned long)ticks_to_sec(app->active_start_tick),
                    app->hw_id);
                cmd_log_set_status(app, app->hw_id, CmdTimeout);
                hw_stop_all(app);
                usb_send(app, tout);
                app->stat_err++;
                snprintf(app->last_result, sizeof(app->last_result), "TIMEOUT");
            }

            /* Update active progress for running HW ops */
            if(app->hw_state != HwIdle) {
                uint32_t elapsed_ms = (furi_get_tick() - app->active_start_tick) * 1000 / furi_ms_to_ticks(1000);
                app->active_progress = (uint8_t)((elapsed_ms * 100) / (HW_TIMEOUT_MS));
                if(app->active_progress > 99) app->active_progress = 99;

                /* SubGHz record has real progress */
                if(app->hw_state == HwSubghzRecord && app->rx_timings_count > 0) {
                    app->active_progress = (uint8_t)((app->rx_timings_count * 100) / RX_MAX_TIMES);
                }

                /* Send periodic progress updates for long ops (every ~2s) */
                if(app->spin_tick % 10 == 0) {
                    usb_send_progress(app, app->hw_id, app->active_progress,
                        hw_state_name(app->hw_state));
                }
            }

            /* Connection timeout: mark disconnected if no cmd for 30s */
            if(app->connected && app->last_cmd_tick > 0) {
                uint32_t idle_sec = ticks_to_sec(app->last_cmd_tick);
                if(idle_sec > 30) app->connected = false;
            }

            view_port_update(app->vp);
            continue;
        }

        if(evts & EvtStop) break;

        if(evts & EvtInput) {
            handle_input(app);
            view_port_update(app->vp);
        }

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
            snprintf(app->last_result, sizeof(app->last_result), "Bridge mode ended");
            view_port_update(app->vp);
        }

        if(evts & EvtHwDone) {
            cmd_log_set_status(app, app->hw_id, CmdOk);
            hw_stop_all(app);
            usb_send(app, app->hw_result_json);
            app->stat_rx++;
            app->active_progress = 100;
            view_port_update(app->vp);
        }

        if(evts & EvtTxDone) {
            furi_hal_subghz_stop_async_tx();
            furi_hal_subghz_sleep();
            app->hw_state = HwIdle;
            static char txresp[128];
            snprintf(txresp, sizeof(txresp),
                "{\"status\":\"ok\",\"repeat\":%d,\"id\":\"%s\"}\n",
                app->tx_repeat_cnt, app->hw_id);
            usb_send(app, txresp);
            cmd_log_set_status(app, app->hw_id, CmdOk);
            app->active_progress = 100;
            snprintf(app->last_result, sizeof(app->last_result),
                "RF TX done: %d repeats", app->tx_repeat_cnt);
            app->active_action[0] = '\0';
        }

        app->spin_tick++;
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
