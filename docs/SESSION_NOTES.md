# Session Notes - Flipper Zero Communication Refactor

## Date: 2026-02-22 (updated)

---

## Current Status: ROOT CAUSE FOUND + FAP rewritten to dual-CDC (needs rebuild + device test)

Build: PASSING ✅
FAP + APK both in GitHub Release v3.0.288 ✅
Runtime communication: root cause confirmed from firmware source 2026-08-20; FAP + APK fixed, rebuild + device test ❓

---

## ROOT CAUSE (CONFIRMED FROM FIRMWARE SOURCE, 2026-08-20)

**The FAP's `cli_vcp_disable()` call kills the Flipper's USB device.**

Verified in flipperzero-firmware (dev branch):
- `cli_vcp_disable()` → `furi_hal_usb_set_config(cli_vcp->previous_interface, NULL)`
- `furi_hal_usb_set_config` → `usb_process_mode_change` → `usbd_connect(&udev, false)`
  (electrical USB disconnect) + deinit, then re-init with a NULL interface = **no USB
  device on the bus at all**. The FAP never re-enables it.

That single call explains BOTH symptoms:
1. **FAP running first → phone doesn't detect the Flipper** — the Flipper's USB is
   deinitialized (not just re-enumerated). The phone sees nothing.
2. **USB first, FAP after → connected but silent** — launching the FAP drops the
   USB; the phone's open port dies and the Flipper never comes back.

Also confirmed: the USB device descriptor is static (VID 0x0483/PID 0x5740 in
`furi_hal_usb_cdc.c`), so the FAP never changes enumeration by itself — only the
`furi_hal_usb_set_config` path does.

## THE FIX (implemented 2026-08-20)

**Dual-CDC: the FAP claims CDC1, the CLI stays on CDC0.**
- `furi_hal_usb_set_config(&usb_cdc_dual, NULL)` + `furi_hal_cdc_set_callbacks(1, ...)`
  (`usb_cdc_dual` and `furi_hal_usb_set_config` ARE exported to FAPs — verified in
  api_symbols.csv). No `cli_vcp_disable` at all.
- All FAP CDC I/O moved to interface 1 (`FAP_CDC_IF`).
- FAP exit restores `usb_cdc_single`; the CLI callbacks on CDC0 are untouched, so the
  CLI stays usable the whole time.
- Android `FlipperUSBManager` opens `driver.ports[1]` (CDC1 = FAP) with fallback to
  `ports[0]` (CDC0 = CLI) when the FAP isn't running. usb-serial-for-android 3.9.0
  supports multi-port CDC devices (one port per CDC-ACM IAD).

Cost: one USB re-enumeration when the FAP launches (single→dual). The app's
connection monitor (added earlier today) already handles detach/attach + reconnect,
so the FAP is detected automatically within a few seconds.

### GUI overhaul (2026-08-20)
- Inverted header bar with pentagram logo, version, and link dot (solid = host
  connected via CDC, blinking = waiting). Link state comes from `cdc_state_cb`.
- Big centered mode line with friendly names ("SUBGHZ RX", "RFID EMULATE", …)
  and a blinking cursor block.
- Progress bar eases toward the real value (+4/frame), pulses at the leading edge
  while a job runs, and shows an idle sweep animation when ready. Percent readout.
- RX line with an activity pulse (triangle flashes when data arrives — USB or UART).
- Rotating radar spinner in the footer; loop tick reduced 250ms → 100ms (~10fps).

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

## Fixes — Status (updated 2026-08-20)

### Fix 1: Wire FlipperHAL.init() to USB connect event — DONE
Replaced by `FapProtocol` (object, always wired). `FlipperUSBManager.onNewData()`
feeds every received line to `FapProtocol.onData()` unconditionally.

### Fix 2: Auto-call initSession on USB connect — DONE
`MainActivity.attemptConnection()` auto-pings the FAP after connect.

### Fix 3: Fix furi_hal_serial_dma_rx_stop in FAP — DONE
FAP cleanup uses `furi_hal_serial_deinit()` only (no dma_rx_stop).

### Fix 4: Better user feedback for FAP status — DONE
Main screen shows "FAP READY" / "FAP NOT RUNNING" / "CONNECTING...".

### Fix 5: Re-enumerate USB after FAP takes over — DONE (was never wired)
`FlipperUSBManager.reopenPort()` existed but was never called. Now wired into
`MainActivity.ensureFapPing()`: while the FAP is silent, each retry round reopens
the USB port to clear stale CDC state; every 3rd round does a full disconnect +
reconnect. Once the FAP responds, the port is left alone (verify rounds only ping).

### Fix 6 (new): Persistent connection monitor — DONE
`MainActivity` now runs a 2s monitor loop:
- Flipper (re)appears in `UsbManager.deviceList` → auto-connect (fixes
  "FAP running first → phone doesn't detect" — app no longer relies on a
  one-shot scan or the ATTACHED broadcast, which misses spoofed VIDs)
- Device disappears → mark disconnected
- Connected but FAP silent → keep pinging until the FAP is launched
- Dynamic `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` receiver registered
  while the activity is resumed
- Permission-dialog spam guard in `FlipperUSBManager.connect()` (the monitor
  would otherwise re-request USB permission every 2s)

### Still open / needs device test
- Confirm on-device: plug Flipper (FAP running) → phone shows FAP READY within
  a few seconds; launch FAP after connecting → flips to FAP READY automatically
- FAP build uses `sdk-channel: release` in CI but user runs Momentum mntm-014.
  If the FAP fails to load / Flipper reboots on launch, rebuild the FAP with
  `ufbt update --channel=<momentum's channel>` matching the installed firmware.

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
