# Session Notes - Flipper Zero Communication Refactor

## Date: 2026-02-22 (updated)

---

## Current Status: FAP BUILDS BUT STILL NOT COMMUNICATING

Build: PASSING ✅
FAP + APK both in GitHub Release v3.0.288 ✅
Runtime communication: BROKEN ❌

---

## What The User Is Seeing

1. **FAP launched first → phone doesn't recognize Flipper**
   - If Pen15 Controller FAP is running on Flipper BEFORE USB is plugged in,
     the phone app does not detect the Flipper at all.

2. **USB first, FAP after → phone connects but no communication**
   - If Flipper plugged in first (CLI running) THEN FAP launched,
     the phone app sees the device is connected, but nothing responds.
     No ping. No response. Silence.

3. **AWOK not recognized at all**
   - Nothing happens with the AWOK board.

---

## Root Cause Analysis (Current Best Understanding)

### Problem A — FlipperHAL.init() never called on USB connect

`FlipperHAL.init()` sets up the JSON response router (data received callback).
It is ONLY called from `FlipperConnectionManager.initSession()`.
`initSession()` is ONLY called from `FlipperGPIOBridge.startBridge()`.
`startBridge()` is ONLY called when user taps the FAB in WiFiDeauthActivity.

**Result:** On a fresh USB connect, FlipperHAL has NO data callback registered.
Any response from the FAP goes nowhere. The ping fires but the response is never routed.

**Fix needed:** Call `FlipperHAL.init()` immediately when USB connection is established
in `FlipperUSBManager`'s connection success callback → `MainActivity`'s connection listener.

---

### Problem B — USB CDC takeover timing

When the user launches the FAP while USB is already connected:
1. FAP calls `cli_vcp_disable()` + `furi_hal_cdc_set_callbacks()`
2. The USB host (phone) may see a brief disconnect/reconnect event
3. The phone's serial port may be in an invalid state
4. Subsequent sends/receives fail silently

**Fix needed:** After FAP is detected as running (ping success), close and reopen
the USB serial port on the Android side to ensure clean state.

---

### Problem C — FAP launch detection: no feedback to user

The app has no way to know if the FAP is actually running on the Flipper.
When `initSession()` sends ping and gets no response, it silently falls back
with "Launch Pen15 Controller on Flipper" message — but this message may not
be shown anywhere visible to the user in the current UI flow.

**Fix needed:** Show a dialog/toast clearly telling user to launch the FAP first,
and provide a "Retry" button.

---

### Problem D — AWOK uart_init not auto-called

`FlipperHAL.uartInit()` must be called before any `uartSend()`.
Currently it is only called inside `FlipperGPIOBridge.startBridge()` after ping.
If `startBridge()` fails at the ping step, `uartInit` never runs.
AWOK never gets initialized.

---

### Problem E — Possible API issue: furi_hal_serial_dma_rx_stop

In the FAP cleanup code I have:
```c
furi_hal_serial_dma_rx_stop(app->serial);
```
This function may NOT exist in the ufbt release SDK. The correct function
from the firmware source is to just call `furi_hal_serial_deinit()` which
stops DMA RX internally. Need to verify and fix.

---

### Problem F — FAP launches first → USB not visible to phone

When the FAP starts and calls `cli_vcp_disable()`, the Flipper's USB CDC
interface state changes. The phone USB host driver may not re-enumerate
the device properly, causing it to appear disconnected to `UsbManager`.

Possible fix: After `cli_vcp_disable`, the FAP should NOT change USB config.
The USB device should remain enumerated. But there may be a CDC control
line state change that confuses Android's USB host.

---

## Fixes Required (Next Session)

### Fix 1: Wire FlipperHAL.init() to USB connect event
File: `MainActivity.kt`
Where: In the connection listener callback where `connectionType = USB` is set
Change: Add `FlipperHAL.init()` call immediately after successful USB connect

### Fix 2: Auto-call initSession on USB connect
File: `FlipperConnectionManager.kt`
Change: In `setConnectionType(USB)` or after connection success, auto-call
`initSession()` in background so HAL is ready without user doing anything extra.

### Fix 3: Fix furi_hal_serial_dma_rx_stop in FAP
File: `fap/pen15_controller/pen15_controller.c`
Change: Remove `furi_hal_serial_dma_rx_stop(app->serial)` — just call
`furi_hal_serial_deinit(app->serial)` directly. DMA rx is stopped by deinit.

### Fix 4: Better user feedback for FAP status
Show clear status on main screen: "FAP Ready" / "FAP Not Running" / "AWOK Ready"

### Fix 5: Re-enumerate USB after FAP takes over
After ping succeeds, force close/reopen the serial port on Android side
to ensure clean CDC state.

---

## Correct Connection Flow (What Should Happen)

```
1. User opens Pen15 app on phone
2. User launches "Pen15 Controller" FAP on Flipper (Apps → Tools)
3. FAP shows "WAIT" on Flipper screen
4. User plugs in USB OTG cable
5. Phone app detects USB device (VID/PID match)
6. Phone opens serial port 115200 8N1, pulses DTR/RTS
7. FlipperHAL.init() called immediately (currently MISSING)
8. initSession() auto-called in background:
   a. Sends {"action":"ping","id":"1"}\r\n
   b. FAP receives, parses, responds {"status":"ok","device":"flipper_zero",...}
   c. Phone receives response → session established
   d. Auto-calls uartInit(115200) to prep AWOK bridge
9. Flipper screen shows "CONN"
10. Phone app shows "FAP Ready" indicator
11. User can now use all features
```

---

## Architecture (Unchanged)

```
Android App (JSON over USB serial)
    → FlipperHAL.kt (HAL layer, pin state registry)
    → FlipperProtocol.kt (JSON builder/parser)
    → FlipperConnectionManager.kt (USB serial transport)
    → [USB OTG cable]
    → Flipper Zero (pen15_controller FAP)
    → furi_hal_gpio_* (direct GPIO control)
    → furi_hal_uart USART1 (GPIO pins 13/14 at 115200)
    → AWOK Dual Mini v3 (ESP32 Marauder)
```

## JSON Protocol (Unchanged - see previous notes)

## File Locations
- FAP source: `fap/pen15_controller/pen15_controller.c`
- FAP manifest: `fap/pen15_controller/application.fam`
- JSON tokenizer: `fap/pen15_controller/jsmn.h`
- Android HAL: `app/.../FlipperHAL.kt`
- Android Protocol: `app/.../FlipperProtocol.kt`
- Session logging: `app/.../SessionLogger.kt`
- CI workflow: `.github/workflows/build.yml`

## Correct Marauder Commands (verified in CLAUDE.md)
- `scanap` — scan WiFi APs
- `stopscan` — stop scan
- `select -a <idx>` — select AP
- `attack -t deauth` — deauth attack
- `sniffpmkid` — PMKID capture
- `sniffbt` — Bluetooth sniff
- `blespam` — BLE spam
- `evilportal` — evil twin portal

## Device Info
- Phone: Samsung Galaxy Note 10+ (Android 11, non-rooted)
- Flipper: Momentum firmware (mntm-014)
- AWOK: Dual Mini v3 (ESP32 Marauder), GPIO pins 13=TX / 14=RX
- USB: OTG cable, phone is USB Host
- Flipper VID=0x0483 PID=0x5740 (stock), may be spoofed on Momentum

## GitHub
- Repo: twoskoops707/Pen15 (public)
- Branch: main — latest: commit 6287999
- Latest Release: v3.0.288 (has both APK + FAP)
