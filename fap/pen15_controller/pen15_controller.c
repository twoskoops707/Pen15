#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_usb_cdc.h>
#include <gui/gui.h>
#include <gui/view_port.h>
#include <cli/cli_vcp.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "jsmn.h"

#define TAG              "Pen15"
#define USB_PKT_LEN      64
#define UART_RX_BUF_SZ   512
#define JSON_BUF_SZ      256
#define DISP_STR_LEN     22
#define MAX_TOKENS       32
#define UART_RX_WAIT_MS  500

typedef enum {
    EvtStop      = (1 << 0),
    EvtUsbRx     = (1 << 1),
    EvtUartRx    = (1 << 2),
} Pen15Evt;

#define ALL_EVENTS (EvtStop | EvtUsbRx | EvtUartRx)

typedef enum { PinUnset = 0, PinInput, PinOutput } PinMode;

typedef struct {
    FuriThread*          thread;
    FuriMutex*           usb_mtx;
    FuriSemaphore*       tx_sem;
    CliVcp*              cli_vcp;

    FuriHalSerialHandle* serial;
    FuriStreamBuffer*    uart_rx_buf;
    bool                 uart_ready;

    char  json_buf[JSON_BUF_SZ];
    size_t json_len;

    char  status[DISP_STR_LEN];
    char  cmd_disp[DISP_STR_LEN];
    char  rx_disp[DISP_STR_LEN];
    uint8_t progress;
    uint8_t spin;

    PinMode pin_mode[8];

    Gui*      gui;
    ViewPort* vp;
} Pen15App;

/* External GPIO header pin map (indices 0-7) — verified from furi_hal_resources.h */
static const GpioPin* const EXT_PINS[8] = {
    &gpio_ext_pa7,  /* 0 → header pin 2  */
    &gpio_ext_pa6,  /* 1 → header pin 3  */
    &gpio_ext_pa4,  /* 2 → header pin 4  */
    &gpio_ext_pb3,  /* 3 → header pin 5  */
    &gpio_ext_pb2,  /* 4 → header pin 6  */
    &gpio_ext_pc3,  /* 5 → header pin 7  */
    &gpio_ext_pc1,  /* 6 → header pin 15 */
    &gpio_ext_pc0,  /* 7 → header pin 16 */
};

static const char* SPIN_CHARS[] = {"|", "/", "-", "\\"};

/* ── CDC callbacks (called in interrupt context) ───────────────── */

static void cdc_on_rx(void* ctx) {
    Pen15App* app = ctx;
    furi_thread_flags_set(furi_thread_get_id(app->thread), EvtUsbRx);
}

static void cdc_on_tx_done(void* ctx) {
    Pen15App* app = ctx;
    furi_semaphore_release(app->tx_sem);
}

static void cdc_state_cb(void* ctx, uint8_t s)              { UNUSED(ctx); UNUSED(s); }
static void cdc_ctrl_cb(void* ctx, uint8_t s)               { UNUSED(ctx); UNUSED(s); }
static void cdc_cfg_cb(void* ctx, struct usb_cdc_line_coding* c) { UNUSED(ctx); UNUSED(c); }

static const CdcCallbacks CDC_CB = {
    cdc_on_tx_done,
    cdc_on_rx,
    cdc_state_cb,
    cdc_ctrl_cb,
    cdc_cfg_cb,
};

/* ── UART RX DMA callback ───────────────────────────────────────── */

static void uart_rx_dma_cb(FuriHalSerialHandle* h, FuriHalSerialRxEvent ev,
                            size_t size, void* ctx) {
    Pen15App* app = ctx;
    if(ev & (FuriHalSerialRxEventData | FuriHalSerialRxEventIdle)) {
        uint8_t tmp[FURI_HAL_SERIAL_DMA_BUFFER_SIZE];
        while(size > 0) {
            size_t got = furi_hal_serial_dma_rx(
                h, tmp, (size > sizeof(tmp)) ? sizeof(tmp) : size);
            furi_stream_buffer_send(app->uart_rx_buf, tmp, got, 0);
            size -= got;
        }
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtUartRx);
    }
}

/* ── USB send ───────────────────────────────────────────────────── */

static void usb_send(Pen15App* app, const char* str) {
    uint16_t len = (uint16_t)strlen(str);
    if(len == 0) return;
    furi_semaphore_acquire(app->tx_sem, 300);
    furi_mutex_acquire(app->usb_mtx, FuriWaitForever);
    furi_hal_cdc_send(0, (uint8_t*)str, len);
    furi_mutex_release(app->usb_mtx);
}

/* ── GUI draw ───────────────────────────────────────────────────── */

static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);

    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2,  10, "PEN15");
    canvas_draw_str(canvas, 44, 10, SPIN_CHARS[app->spin & 3]);
    canvas_draw_str(canvas, 60, 10, app->status);

    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 2,  22, "CMD:");
    canvas_draw_str(canvas, 30, 22, app->cmd_disp);

    canvas_draw_frame(canvas, 2, 27, 124, 5);
    uint8_t fill = (app->progress > 100) ? 124 : (uint8_t)((app->progress * 124) / 100);
    if(fill > 0) canvas_draw_box(canvas, 2, 27, fill, 5);

    canvas_draw_str(canvas, 2,  42, "RX:");
    canvas_draw_str(canvas, 22, 42, app->rx_disp);

    canvas_draw_str(canvas, 2,  62, "[BACK] exit");
}

static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(ev->type == InputTypeShort && ev->key == InputKeyBack) {
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop);
    }
}

/* ── jsmn helpers ───────────────────────────────────────────────── */

static bool tok_eq(const char* js, const jsmntok_t* t, const char* s) {
    size_t tlen = (size_t)(t->end - t->start);
    return (t->type == JSMN_STRING || t->type == JSMN_PRIMITIVE) &&
           strlen(s) == tlen &&
           strncmp(js + t->start, s, tlen) == 0;
}

static bool json_str(const char* js, jsmntok_t* toks, int n,
                     const char* key, char* out, size_t out_sz) {
    for(int i = 1; i < n - 1; i += 2) {
        if(tok_eq(js, &toks[i], key)) {
            size_t vlen = (size_t)(toks[i + 1].end - toks[i + 1].start);
            if(vlen >= out_sz) vlen = out_sz - 1;
            memcpy(out, js + toks[i + 1].start, vlen);
            out[vlen] = '\0';
            return true;
        }
    }
    return false;
}

static int json_int(const char* js, jsmntok_t* toks, int n,
                    const char* key, int def) {
    char tmp[16] = {0};
    if(json_str(js, toks, n, key, tmp, sizeof(tmp))) return atoi(tmp);
    return def;
}

/* ── Command dispatch ───────────────────────────────────────────── */

static void handle_json(Pen15App* app, const char* js, size_t len) {
    jsmn_parser  parser;
    jsmntok_t    toks[MAX_TOKENS];
    static char  resp[JSON_BUF_SZ];
    char         action[32] = {0};
    char         id[16]     = {0};

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

    /* ── ping ──────────────────────────────────────────────────── */
    if(strcmp(action, "ping") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap\":\"1.0\",\"id\":\"%s\"}\n", id);
        usb_send(app, resp);
        strncpy(app->status,  "CONN", sizeof(app->status) - 1);
        strncpy(app->rx_disp, "ping ok", sizeof(app->rx_disp) - 1);
        app->progress = 100;

    /* ── gpio_mode ─────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_mode") == 0) {
        int  pin  = json_int(js, toks, n, "pin", -1);
        char mode[16] = {0};
        json_str(js, toks, n, "mode", mode, sizeof(mode));

        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else {
            if(strcmp(mode, "output") == 0) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeOutputPushPull,
                                   GpioPullNo, GpioSpeedMedium);
                app->pin_mode[pin] = PinOutput;
            } else {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput,
                                   GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"mode\":\"%s\",\"id\":\"%s\"}\n",
                pin, mode, id);
            strncpy(app->rx_disp, "gpio_mode ok", sizeof(app->rx_disp) - 1);
        }
        app->progress = 100;
        usb_send(app, resp);

    /* ── gpio_write ────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_write") == 0) {
        int pin   = json_int(js, toks, n, "pin",   -1);
        int value = json_int(js, toks, n, "value", -1);

        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else if(app->pin_mode[pin] != PinOutput) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"NOT_OUTPUT\","
                "\"message\":\"Call gpio_mode output first\",\"id\":\"%s\"}\n", id);
        } else {
            furi_hal_gpio_write(EXT_PINS[pin], value != 0);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, value != 0 ? 1 : 0, id);
            strncpy(app->rx_disp, "gpio ok", sizeof(app->rx_disp) - 1);
        }
        app->progress = 100;
        usb_send(app, resp);

    /* ── gpio_read ─────────────────────────────────────────────── */
    } else if(strcmp(action, "gpio_read") == 0) {
        int pin = json_int(js, toks, n, "pin", -1);

        if(pin < 0 || pin > 7) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"BAD_PIN\",\"id\":\"%s\"}\n", id);
        } else {
            if(app->pin_mode[pin] == PinUnset) {
                furi_hal_gpio_init(EXT_PINS[pin], GpioModeInput,
                                   GpioPullNo, GpioSpeedLow);
                app->pin_mode[pin] = PinInput;
            }
            bool val = furi_hal_gpio_read(EXT_PINS[pin]);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"pin\":%d,\"value\":%d,\"id\":\"%s\"}\n",
                pin, val ? 1 : 0, id);
            strncpy(app->rx_disp, "gpio read ok", sizeof(app->rx_disp) - 1);
        }
        app->progress = 100;
        usb_send(app, resp);

    /* ── uart_init ─────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_init") == 0) {
        int baud = json_int(js, toks, n, "baud", 115200);

        if(!app->uart_ready) {
            app->serial = furi_hal_serial_control_acquire(FuriHalSerialIdUsart);
            if(app->serial) {
                furi_hal_serial_init(app->serial, (uint32_t)baud);
                furi_hal_serial_dma_rx_start(
                    app->serial, uart_rx_dma_cb, app, false);
                app->uart_ready = true;
                snprintf(resp, sizeof(resp),
                    "{\"status\":\"ok\",\"baud\":%d,\"id\":\"%s\"}\n", baud, id);
                strncpy(app->rx_disp, "uart ready", sizeof(app->rx_disp) - 1);
            } else {
                snprintf(resp, sizeof(resp),
                    "{\"status\":\"error\",\"code\":\"UART_BUSY\",\"id\":\"%s\"}\n", id);
            }
        } else {
            furi_hal_serial_set_br(app->serial, (uint32_t)baud);
            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"baud\":%d,\"id\":\"%s\"}\n", baud, id);
            strncpy(app->rx_disp, "uart baud set", sizeof(app->rx_disp) - 1);
        }
        app->progress = 100;
        usb_send(app, resp);

    /* ── uart_send ─────────────────────────────────────────────── */
    } else if(strcmp(action, "uart_send") == 0) {
        static char data[128];
        memset(data, 0, sizeof(data));
        json_str(js, toks, n, "data", data, sizeof(data));

        if(!app->uart_ready) {
            snprintf(resp, sizeof(resp),
                "{\"status\":\"error\",\"code\":\"UART_NOT_INIT\",\"id\":\"%s\"}\n", id);
            usb_send(app, resp);
        } else {
            furi_stream_buffer_reset(app->uart_rx_buf);
            furi_hal_serial_tx(app->serial, (uint8_t*)data, strlen(data));
            furi_hal_serial_tx_wait_complete(app->serial);

            /* Collect AWOK response for UART_RX_WAIT_MS */
            static char awok[200];
            memset(awok, 0, sizeof(awok));
            size_t   awok_len  = 0;
            uint32_t deadline  = furi_get_tick() + furi_ms_to_ticks(UART_RX_WAIT_MS);

            while(furi_get_tick() < deadline && awok_len < sizeof(awok) - 1) {
                uint8_t b;
                size_t  got = furi_stream_buffer_receive(
                    app->uart_rx_buf, &b, 1, 0);
                if(got > 0) {
                    awok[awok_len++] = (char)b;
                } else {
                    furi_delay_ms(5);
                }
                app->spin++;
                view_port_update(app->vp);
            }
            awok[awok_len] = '\0';

            /* Basic JSON escape: replace " → ' and control chars → space */
            for(size_t i = 0; i < awok_len; i++) {
                if(awok[i] == '"')  awok[i] = '\'';
                if(awok[i] == '\r') awok[i] = ' ';
                if(awok[i] == '\n') awok[i] = '|';
                if((unsigned char)awok[i] < 32) awok[i] = ' ';
            }

            snprintf(resp, sizeof(resp),
                "{\"status\":\"ok\",\"uart_rx\":\"%s\",\"id\":\"%s\"}\n",
                awok, id);
            app->progress = 100;
            usb_send(app, resp);

            /* Show first 20 chars of AWOK response on screen */
            strncpy(app->rx_disp, awok_len > 0 ? awok : "(no rx)",
                    sizeof(app->rx_disp) - 1);
        }

    /* ── get_device_info ───────────────────────────────────────── */
    } else if(strcmp(action, "get_device_info") == 0) {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"ok\",\"device\":\"flipper_zero\","
            "\"fw\":\"mntm\",\"fap_ver\":\"1.0\",\"id\":\"%s\"}\n", id);
        app->progress = 100;
        usb_send(app, resp);
        strncpy(app->rx_disp, "info sent", sizeof(app->rx_disp) - 1);

    /* ── unknown ───────────────────────────────────────────────── */
    } else {
        snprintf(resp, sizeof(resp),
            "{\"status\":\"error\",\"code\":\"UNKNOWN\","
            "\"message\":\"%s\",\"id\":\"%s\"}\n", action, id);
        app->progress = 0;
        usb_send(app, resp);
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

    /* Thread handle must be stored before any callback is registered */
    app->thread      = furi_thread_get_current();
    app->usb_mtx     = furi_mutex_alloc(FuriMutexTypeNormal);
    app->tx_sem      = furi_semaphore_alloc(1, 1);
    app->uart_rx_buf = furi_stream_buffer_alloc(UART_RX_BUF_SZ, 1);

    strncpy(app->status,   "WAIT", sizeof(app->status) - 1);
    strncpy(app->cmd_disp, "---",  sizeof(app->cmd_disp) - 1);
    strncpy(app->rx_disp,  "---",  sizeof(app->rx_disp) - 1);

    /* GUI */
    app->vp  = view_port_alloc();
    app->gui = furi_record_open(RECORD_GUI);
    view_port_draw_callback_set(app->vp,  draw_cb,  app);
    view_port_input_callback_set(app->vp, input_cb, app);
    gui_add_view_port(app->gui, app->vp, GuiLayerFullscreen);

    /* Take over USB CDC — disable CLI VCP, register our callbacks */
    app->cli_vcp = furi_record_open(RECORD_CLI_VCP);
    cli_vcp_disable(app->cli_vcp);
    furi_hal_cdc_set_callbacks(0, (CdcCallbacks*)&CDC_CB, app);

    /* Main event loop */
    while(true) {
        uint32_t evts = furi_thread_flags_wait(
            ALL_EVENTS, FuriFlagWaitAny, 250);

        if(evts & FuriFlagError) {
            /* Timeout — update spin animation */
            app->spin++;
            view_port_update(app->vp);
            continue;
        }

        if(evts & EvtStop) break;
        if(evts & EvtUsbRx) process_usb_rx(app);
        /* EvtUartRx: data is in uart_rx_buf, consumed inside handle_json uart_send */

        app->spin++;
        view_port_update(app->vp);
    }

    /* ── Cleanup ──────────────────────────────────────────────── */

    /* Restore USB to CLI */
    furi_hal_cdc_set_callbacks(0, NULL, NULL);
    cli_vcp_enable(app->cli_vcp);
    furi_record_close(RECORD_CLI_VCP);

    /* Release UART */
    if(app->uart_ready && app->serial) {
        furi_hal_serial_dma_rx_stop(app->serial);
        furi_hal_serial_deinit(app->serial);
        furi_hal_serial_control_release(app->serial);
    }

    /* Reset GPIO pins to analog (safe/high-impedance) */
    for(int i = 0; i < 8; i++) {
        if(app->pin_mode[i] != PinUnset) {
            furi_hal_gpio_init(EXT_PINS[i], GpioModeAnalog,
                               GpioPullNo, GpioSpeedLow);
        }
    }

    /* GUI teardown */
    gui_remove_view_port(app->gui, app->vp);
    view_port_free(app->vp);
    furi_record_close(RECORD_GUI);

    furi_stream_buffer_free(app->uart_rx_buf);
    furi_semaphore_free(app->tx_sem);
    furi_mutex_free(app->usb_mtx);
    free(app);

    return 0;
}
