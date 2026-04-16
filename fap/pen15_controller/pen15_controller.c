#include <stdint.h>
/* ═══════════════════════════════════════════════════════════════════
   PEN15 Controller — Modern Flipper UI
   Cyberpunk aesthetic on 128x64 monochrome LCD
   ═══════════════════════════════════════════════════════════════════ */

/* ── Glyph data for large digits (5x5) ───────────────────────────── */
static const uint8_t GLYPH_0[] = {0x1F,0x11,0x11,0x11,0x1F}; // 0
static const uint8_t GLYPH_1[] = {0x04,0x0C,0x04,0x04,0x1F}; // 1
static const uint8_t GLYPH_2[] = {0x1F,0x02,0x1F,0x10,0x1F}; // 2
static const uint8_t GLYPH_3[] = {0x1F,0x02,0x0F,0x02,0x1F}; // 3
static const uint8_t GLYPH_4[] = {0x11,0x11,0x1F,0x01,0x01}; // 4
static const uint8_t GLYPH_5[] = {0x1F,0x10,0x1F,0x02,0x1F}; // 5
static const uint8_t GLYPH_6[] = {0x1F,0x10,0x1F,0x11,0x1F}; // 6
static const uint8_t GLYPH_7[] = {0x1F,0x01,0x02,0x04,0x08}; // 7
static const uint8_t GLYPH_8[] = {0x1F,0x11,0x1F,0x11,0x1F}; // 8
static const uint8_t GLYPH_9[] = {0x1F,0x11,0x1F,0x02,0x1F}; // 9
 // :
 // -

static const uint8_t* GLYPHS[] = {
    GLYPH_0, GLYPH_1, GLYPH_2, GLYPH_3, GLYPH_4,
    GLYPH_5, GLYPH_6, GLYPH_7, GLYPH_8, GLYPH_9,
};

/* ── Helper: draw pixel at (x,y) with alpha blend ─────────────────── */
static void draw_pixel(Canvas* c, int x, int y, bool on) {
    if(x < 0 || x > 127 || y < 0 || y > 63) return;
    canvas_set_color(c, on ? ColorXOR : ColorBlack);
    canvas_draw_dot(c, (uint8_t)x, (uint8_t)y);
}

/* ── Draw filled rect with optional glow border ───────────────────── */
static void draw_box(Canvas* c, int x, int y, int w, int h, bool filled) {
    if(filled) {
        canvas_draw_box(c, (uint8_t)x, (uint8_t)y, (uint8_t)w, (uint8_t)h);
    } else {
        canvas_draw_frame(c, (uint8_t)x, (uint8_t)y, (uint8_t)w, (uint8_t)h);
    }
}

/* ── Draw horizontal rule with gap ──────────────────────────────── */
static void draw_hrule(Canvas* c, int y, int x1, int x2, bool thick) {
    for(int x = x1; x <= x2; x++) {
        canvas_draw_dot(c, (uint8_t)x, (uint8_t)y);
        if(thick && y + 1 <= 63) canvas_draw_dot(c, (uint8_t)x, (uint8_t)(y+1));
    }
}

/* ── Draw thin vertical rule ─────────────────────────────────────── */
static void draw_vrule(Canvas* c, int x, int y1, int y2) {
    for(int y = y1; y <= y2; y++) {
        canvas_draw_dot(c, (uint8_t)x, (uint8_t)y);
    }
}

/* ── Mode badge strings ───────────────────────────────────────────── */
static const char* mode_label(Pen15App* app) {
    switch(app->app_mode) {
        case ModeBridge: return app->bridge_mode ?  BRIDGE_MODE_STR : BRIDGE_MODE_STR;
        default: break;
    }
    switch(app->hw_state) {
        case HwRfidRead:     return app->hw_state == HwRfidRead     ? RFID_MODE_STR  : IDLE_STR;
        case HwRfidEmulate:  return RFID_EM_STR;
        case HwNfcDetect:    return NFC_MODE_STR;
        case HwIrRx:         return IR_MODE_STR;
        case HwIkeyRead:     return KEY_MODE_STR;
        case HwIkeyEmulate:  return KEY_EM_STR;
        case HwSubghzRx:     return RX_MODE_STR;
        case HwSubghzRecord: return REC_MODE_STR;
        case HwSubghzTx:     return TX_MODE_STR;
        default:             return IDLE_STR;
    }
}

/* ── Mode badge color (grayscale dot pattern) ──────────────────────── */
/* Flipper only has black/white — we use fill pattern instead */
static void draw_mode_badge(Canvas* c, int x, int y, const char* label, bool active) {
    int len = (int)strlen(label);
    int bw = len * 5 + 4;
    int bh = 9;
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c, (uint8_t)x, (uint8_t)y, (uint8_t)bw, (uint8_t)bh);
    canvas_set_color(c, ColorWhite);
    canvas_draw_box(c, (uint8_t)(x+1), (uint8_t)(y+1), (uint8_t)(bw-2), (uint8_t)(bh-2));
    canvas_set_color(c, active ? ColorBlack : ColorXOR);
    canvas_set_font(c, FontSecondary);
    canvas_draw_str(c, (uint8_t)(x+3), (uint8_t)(y+7), label);
}

/* ── Large digit drawer ────────────────────────────────────────────── */
static void draw_digit(Canvas* c, int x, int y, int d) {
    if(d < 0 || d > 9) return;
    const uint8_t* g = GLYPHS[d];
    for(int row = 0; row < 5; row++) {
        uint8_t bits = g[row];
        for(int col = 0; col < 5; col++) {
            if(bits & (1 << (4-col))) {
                canvas_draw_dot(c, (uint8_t)(x+col), (uint8_t)(y+row));
            }
        }
    }
}

/* ── Progress bar (two-tone with segments) ─────────────────────────── */
static void draw_progress_bar(Canvas* c, int x, int y, int w, int h, uint8_t pct) {
    canvas_set_color(c, ColorXOR);
    canvas_draw_box(c, (uint8_t)x, (uint8_t)y, (uint8_t)w, (uint8_t)h);
    int fill = (int)((uint16_t)pct * w / 100);
    if(fill > w) fill = w;
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c, (uint8_t)x, (uint8_t)y, (uint8_t)fill, (uint8_t)h);
    /* segment dividers */
    canvas_set_color(c, ColorWhite);
    for(int i = 1; i < 4; i++) {
        canvas_draw_dot(c, (uint8_t)(x + i*w/4), (uint8_t)y);
        canvas_draw_dot(c, (uint8_t)(x + i*w/4), (uint8_t)(y+h-1));
    }
}

/* ── Loading spinner (rotating arc segments) ──────────────────────── */
static void draw_spinner(Canvas* c, int cx, int cy, int r, uint8_t phase) {
    for(int i = 0; i < 8; i++) {
        float angle = (i * 3.14159f / 4.0f) + (phase * 0.1f);
        int ax = cx + (int)(r * cosf(angle));
        int ay = cy + (int)(r * sinf(angle));
        int brightness = ((i + phase) % 4 == 0) ? 1 : 0;
        canvas_set_color(c, brightness ? ColorBlack : ColorWhite);
        canvas_draw_box(c, (uint8_t)ax, (uint8_t)ay, 2, 2);
    }
}

/* ── Signal strength bar (3 bars) ─────────────────────────────────── */
static void draw_signal_bars(Canvas* c, int x, int y, int level) {
    int heights[] = {4, 7, 10};
    for(int i = 0; i < 3; i++) {
        int h = (i < level) ? heights[i] : 2;
        int w = 3;
        canvas_set_color(c, i < level ? ColorBlack : ColorXOR);
        canvas_draw_box(c, (uint8_t)(x + i*4), (uint8_t)(y + 12 - h), (uint8_t)w, (uint8_t)h);
    }
}

/* ── ASCII art PEN15 logo (header) ─────────────────────────────────── */
static void draw_logo(Canvas* c, int y) {
    /* Style 1: blocky pixel logo */
    canvas_set_color(c, ColorBlack);
    canvas_set_font(c, FontPrimary);
    canvas_draw_str(c, 2, (uint8_t)(y+10),  PENTS_STR);
    canvas_draw_str(c, 50, (uint8_t)(y+10), FIFTS_STR);
    /* underline accent */
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c, 2, (uint8_t)(y+12), 36, 2);
    canvas_draw_box(c, 50, (uint8_t)(y+12), 36, 2);
}

/* ── Main draw callback — complete modern UI ─────────────────────── */
/* ═══════════════════════════════════════════════════════════════════
   PEN15 Controller — Modern Flipper UI
   Cyberpunk aesthetic on 128x64 monochrome LCD
   ═══════════════════════════════════════════════════════════════════ */

/* ── Glyph data for large digits (5x5) ───────────────────────────── */
static const uint8_t GLYPH_0[] = {0x1F,0x11,0x11,0x11,0x1F};
static const uint8_t GLYPH_1[] = {0x04,0x0C,0x04,0x04,0x1F};
static const uint8_t GLYPH_2[] = {0x1F,0x02,0x1F,0x10,0x1F};
static const uint8_t GLYPH_3[] = {0x1F,0x02,0x0F,0x02,0x1F};
static const uint8_t GLYPH_4[] = {0x11,0x11,0x1F,0x01,0x01};
static const uint8_t GLYPH_5[] = {0x1F,0x10,0x1F,0x02,0x1F};
static const uint8_t GLYPH_6[] = {0x1F,0x10,0x1F,0x11,0x1F};
static const uint8_t GLYPH_7[] = {0x1F,0x01,0x02,0x04,0x08};
static const uint8_t GLYPH_8[] = {0x1F,0x11,0x1F,0x11,0x1F};
static const uint8_t GLYPH_9[] = {0x1F,0x11,0x1F,0x02,0x1F};
static const uint8_t* GLYPHS[] = {
    GLYPH_0,GLYPH_1,GLYPH_2,GLYPH_3,GLYPH_4,
    GLYPH_5,GLYPH_6,GLYPH_7,GLYPH_8,GLYPH_9};

/* ── Mode badge strings ───────────────────────────────────────────── */










static const char* mode_label(Pen15App* app) {
    if(app->app_mode == ModeBridge) return BRIDGE_STR;
    switch(app->hw_state) {
        case HwRfidRead:    return RFID_STR;
        case HwRfidEmulate: return RFID_EM_STR;
        case HwNfcDetect:   return NFC_STR;
        case HwIrRx:        return IR_STR;
        case HwIkeyRead:     return KEY_STR;
        case HwIkeyEmulate: return KEY_EM_STR;
        case HwSubghzRx:
        case HwSubghzRecord: return "REC";
        case HwSubghzTx:     return RF_STR;
        default:            return IDLE_STR;
    }
}

/* ── Progress bar (segmented) ─────────────────────────────────── */
static void draw_pbar(Canvas* c, int x, int y, int w, int h, uint8_t pct) {
    canvas_set_color(c, ColorXOR);
    canvas_draw_box(c,(uint8_t)x,(uint8_t)y,(uint8_t)w,(uint8_t)h);
    int fill = (int)((uint16_t)pct * w / 100);
    if(fill > w) fill = w;
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c,(uint8_t)x,(uint8_t)y,(uint8_t)fill,(uint8_t)h);
}

/* ── Spinner (rotating arc) ────────────────────────────────────── */
static void draw_spin(Canvas* c, int cx, int cy, uint8_t phase) {
    for(int i=0;i<8;i++) {
        float a = i*0.785f + phase*0.1f;
        int ax = cx + (int)(6*cosf(a));
        int ay = cy + (int)(6*sinf(a));
        canvas_set_color(c, (i+phase)%4==0 ? ColorBlack : ColorWhite);
        canvas_draw_box(c,(uint8_t)ax,(uint8_t)ay,2,2);
    }
}

/* ── Signal bars ──────────────────────────────────────────────────── */
static void draw_sig(Canvas* c, int x, int y, int lvl) {
    int h[3] = {4,7,10};
    for(int i=0;i<3;i++) {
        int bh = (i<lvl)?h[i]:2;
        canvas_set_color(c, i<lvl ? ColorBlack : ColorXOR);
        canvas_draw_box(c,(uint8_t)(x+i*4),(uint8_t)(y+12-bh),3,(uint8_t)bh);
    }
}

/* ── Mode badge ─────────────────────────────────────────────────── */
static void draw_badge(Canvas* c, int x, int y, const char* lbl, bool active) {
    int bw = (int)strlen(lbl)*5+6;
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c,(uint8_t)x,(uint8_t)y,(uint8_t)bw,9);
    canvas_set_color(c, ColorWhite);
    canvas_draw_box(c,(uint8_t)(x+1),(uint8_t)(y+1),(uint8_t)(bw-2),7);
    canvas_set_color(c, active ? ColorBlack : ColorXOR);
    canvas_set_font(c, FontSecondary);
    canvas_draw_str(c,(uint8_t)(x+3),(uint8_t)(y+7),lbl);
}

/* ── Header bar ──────────────────────────────────────────────────── */
static void draw_header(Canvas* c) {
    canvas_set_color(c, ColorBlack);
    canvas_draw_box(c,0,0,128,14);
    canvas_set_color(c, ColorWhite);
    canvas_set_font(c, FontPrimary);
    canvas_draw_str(c,2,7,"PEN");
    canvas_draw_str(c,36,7,"15");
    canvas_draw_str(c,55,7,"v2.0");
    if(app->bridge_mode) {
        canvas_set_color(c, ColorXOR);
        canvas_draw_box(c,96,2,30,10);
        canvas_set_color(c, ColorBlack);
        canvas_draw_str(c,98,10,BRIDGE_STR);
    }
    canvas_set_color(c, ColorWhite);
    canvas_draw_box(c,0,13,128,1);
}

/* ── H rule ─────────────────────────────────────────────────────── */
static void draw_hr(Canvas* c, int y) {
    canvas_set_color(c, ColorXOR);
    for(int x=0;x<128;x++) canvas_draw_dot(c,(uint8_t)x,(uint8_t)y);
}

/* ═══════════════════════════════════════════════════════════════════
   MODERN DRAW CALLBACK
   ═══════════════════════════════════════════════════════════════════ */
static void draw_cb(Canvas* canvas, void* ctx) {
    Pen15App* app = ctx;
    canvas_clear(canvas);
    draw_header(canvas);

    int my = 16;
    const char* mode = mode_label(app);
    draw_badge(canvas, 2, my, mode, app->hw_state != HwIdle);
    draw_sig(canvas, 90, my, app->hw_state!=HwIdle ? 3 : 0);

    draw_hr(canvas, my+11);

    int zy = my+14;
    canvas_set_font(canvas, FontPrimary);

    switch(app->hw_state) {
        case HwRfidRead:
        case HwRfidEmulate:
            canvas_draw_str(canvas,2,zy,RFID_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),
                app->hw_state==HwRfidRead?"TAP CARD...":"EMULATING");
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            if(app->hw_state==HwRfidEmulate) draw_spin(canvas,116,zy+10,app->spin);
            break;
        case HwNfcDetect:
            canvas_draw_str(canvas,2,zy,NFC_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),"SCANNING...");
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            break;
        case HwIrRx:
            canvas_draw_str(canvas,2,zy,IR_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),"AIM REMOTE...");
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            break;
        case HwIkeyRead:
        case HwIkeyEmulate:
            canvas_draw_str(canvas,2,zy,KEY_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),
                app->hw_state==HwIkeyRead?"TOUCH KEY...":"EMULATING");
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            break;
        case HwSubghzRx:
        case HwSubghzRecord:
            canvas_draw_str(canvas,2,zy,RF_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),"RX SIGNAL");
            canvas_draw_str(canvas,50,(uint8_t)(zy+9),app->rx_disp);
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            break;
        case HwSubghzTx:
            canvas_draw_str(canvas,2,zy,RF_STR);
            canvas_set_font(canvas,FontSecondary);
            canvas_draw_str(canvas,2,(uint8_t)(zy+9),"TX SIGNAL");
            draw_pbar(canvas,2,zy+19,124,5,app->progress);
            break;
        default:
            if(app->app_mode==ModeBridge) {
                canvas_set_font(canvas,FontSecondary);
                canvas_draw_str(canvas,2,zy,"BRIDGE: RX");
                canvas_draw_str(canvas,70,(uint8_t)zy,app->rx_disp);
                draw_spin(canvas,116,zy+2,app->spin);
            } else {
                canvas_draw_str(canvas,2,zy,"PEN15 READY");
                canvas_set_font(canvas,FontSecondary);
                canvas_draw_str(canvas,2,(uint8_t)(zy+9),"CONNECTED TO PHONE");
            }
            break;
    }

    /* Status bar */
    draw_hr(canvas,51);
    canvas_set_font(canvas,FontSecondary);
    canvas_set_color(canvas,ColorXOR);
    canvas_draw_str(canvas,2,57,"RX:");
    if(app->rx_disp[0]) {
        char tmp[24];
        int len=(int)strlen(app->rx_disp);
        if(len>22){memcpy(tmp,app->rx_disp,19);tmp[19]='.';tmp[20]='.';tmp[21]='.';tmp[22]=0;}
        else strncpy(tmp,app->rx_disp,23);
        canvas_draw_str(canvas,20,57,tmp);
    } else {
        canvas_draw_str(canvas,20,57,"---");
    }
    draw_spin(canvas,110,55,app->spin);

    canvas_set_color(canvas,ColorXOR);
    canvas_draw_str(canvas,74,63,"[BACK] EXIT");
}

static void input_cb(InputEvent* ev, void* ctx) {
    Pen15App* app = ctx;
    if(ev->type == InputTypeShort && ev->key == InputKeyBack)
        furi_thread_flags_set(furi_thread_get_id(app->thread), EvtStop);
}

/* ═══════════════════════════════════════════════════════════════════
   Remaining: all other original code stays exactly the same
   (CDC callbacks, UART RX DMA, USB send, JSON parsing, hardware workers, etc.)
   ═══════════════════════════════════════════════════════════════════ */
/* ═══════════════════════════════════════════════════════════════════
   Remaining: all other original code stays exactly the same
   (CDC callbacks, UART, JSON parsing, hardware workers, etc.)
   ═══════════════════════════════════════════════════════════════════ */