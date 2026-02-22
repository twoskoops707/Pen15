# Session Notes - Flipper Zero Communication Refactor

## Date: 2026-02-22

## Root Cause Identified
The phone detects the Flipper via USB OTG and the app recognizes it, but **zero data flows in either direction**. The Flipper charges off the phone battery but no serial communication works.

**Why it fails:** The current code sends raw Flipper CLI text commands (e.g. `gpio mode 5 1`, `gpio uart_tx`, `loader open "USB-UART Bridge"`). The Flipper CLI requires reading a `>: ` prompt before it accepts commands — the app never does this. Additionally, several commands sent are completely wrong (e.g. `gpio uart_tx` is not a real Flipper CLI command).

## Agreed Solution
Build a **custom Flipper FAP** (external application, written in C) that:
- Runs on the Flipper Zero
- Takes ownership of the USB CDC serial port (bypasses the CLI entirely)
- Speaks a clean **JSON protocol** with the Android app
- Controls GPIO directly via `furi_hal_gpio_*`
- Bridges UART to the AWOK Dual Mini v3 via USART1 (GPIO pins 13/14)
- Shows received commands on Flipper screen with progress bar animation
- Returns JSON responses back to the phone

The Android app sends structured JSON commands → FAP executes → JSON response returned.

## Architecture
```
Android App (JSON over USB serial)
    → FlipperHAL.kt (HAL layer, pin state registry)
    → FlipperProtocol.kt (JSON builder/parser)
    → FlipperConnectionManager.kt (USB serial transport)
    → [USB OTG cable]
    → Flipper Zero (pen15_controller FAP)
    → furi_hal_gpio_* (direct GPIO control)
    → furi_hal_uart (USART1 at 115200, GPIO pins 13/14)
    → AWOK Dual Mini v3 (ESP32 Marauder)
```

## JSON Protocol

### Session Init (app → FAP)
```json
{"action":"ping","id":"1"}
```
### Session Init Response (FAP → app)
```json
{"status":"ok","device":"flipper_zero","fw":"mntm-014","id":"1"}
```

### GPIO Mode
```json
{"action":"gpio_mode","pin":5,"mode":"output","id":"2"}
{"status":"ok","pin":5,"id":"2"}
```

### GPIO Write
```json
{"action":"gpio_write","pin":5,"value":1,"id":"3"}
{"status":"ok","pin":5,"value":1,"id":"3"}
```

### GPIO Read
```json
{"action":"gpio_read","pin":5,"id":"4"}
{"status":"ok","pin":5,"value":1,"id":"4"}
```

### UART Init (for AWOK bridge)
```json
{"action":"uart_init","baud":115200,"id":"5"}
{"status":"ok","id":"5"}
```

### UART Send to AWOK (Marauder command)
```json
{"action":"uart_send","data":"scanap\r\n","id":"6"}
{"status":"ok","uart_rx":"[scan results here]","id":"6"}
```

### Error response
```json
{"status":"error","code":"INVALID_PIN_MODE","message":"Pin 5 not configured as output","id":"3"}
```

## FAP Session Flow
1. User plugs Flipper into phone via USB OTG
2. App detects Flipper USB device (VID/PID matching)
3. App opens USB CDC serial at 115200 8N1, pulses DTR/RTS
4. App sends `loader open "Pen15 Controller"\r\n` (raw CLI — FAP not yet running)
5. App waits 2000ms for FAP to start
6. FAP takes over USB serial
7. App sends `{"action":"ping","id":"1"}\n`
8. FAP responds `{"status":"ok","device":"flipper_zero",...}\n`
9. Session established — all further communication is JSON

## FAP Source Location
`fap/pen15_controller/` in this repo:
- `application.fam` — FAP manifest
- `pen15_controller.c` — main C source
- `jsmn.h` — lightweight JSON tokenizer (MIT license)

## FAP Compilation
The FAP is compiled using **ufbt** (micro Flipper Build Tool) in GitHub Actions.
It is attached to the GitHub Release alongside the APK.
- Download `pen15_controller.fap` from the GitHub Release page
- Copy to Flipper SD card: `/apps/Tools/pen15_controller.fap`
- Run from Flipper: Apps → Tools → Pen15 Controller

## Files To Create (NOT YET DONE - pending next session)
| File | Status |
|------|--------|
| `fap/pen15_controller/application.fam` | TODO |
| `fap/pen15_controller/jsmn.h` | TODO |
| `fap/pen15_controller/pen15_controller.c` | TODO |
| `app/.../FlipperProtocol.kt` | TODO |
| `app/.../FlipperHAL.kt` | TODO |
| `app/.../SessionLogger.kt` | TODO |

## Files To Modify (NOT YET DONE - pending next session)
| File | Change |
|------|--------|
| `FlipperConnectionManager.kt` | Add `initSession()` + JSON response router |
| `FlipperGPIOBridge.kt` | Point to FAP instead of USB-UART Bridge app |
| `GPIOActivity.kt` | Fix wrong commands (`gpio uart_tx` etc) |
| `WiFiDeauthActivity.kt` | Route through HAL |
| `.github/workflows/build.yml` | Add ufbt FAP build + attach to release |

## Current Wrong Commands (GPIOActivity.kt)
These are NOT real Flipper CLI commands and must be replaced:
- `gpio uart_tx` → FAKE, does not exist
- `deauth -a` → wrong Marauder syntax (correct: `attack -t deauth`)
- `scan -w` → wrong Marauder syntax (correct: `scanap`)
- `ap -s FreeWiFi -p password123` → not a real Marauder command

## Correct Marauder Commands (via UART bridge)
From the CLAUDE.md verified list:
- `scanap` — scan WiFi APs
- `stopscan` — stop scan
- `select -a <idx>` — select AP by index
- `attack -t deauth` — deauth attack
- `sniffpmkid` — PMKID capture
- `sniffbt` — Bluetooth sniff
- `blespam` — BLE spam
- `evilportal` — evil twin portal

## Flipper SDK Research Needed (Before Writing FAP)
Before writing pen15_controller.c, MUST research:
1. Exact USB CDC serial read/write API in a FAP context (not CLI context)
2. furi_hal_uart API for USART1 (pins 13/14)
3. furi_hal_gpio API — exact GpioPin constants for external header
4. application.fam exact syntax for current ufbt SDK version
5. How to build with ufbt in GitHub Actions

The user explicitly said: DO NOT GUESS on the FAP. Research first.

## Device Info
- **Phone:** Samsung Galaxy Note 10+ (Android 11, non-rooted)
- **Flipper:** Momentum firmware (mntm-014)
- **AWOK:** Dual Mini v3 (ESP32 Marauder), connected to Flipper GPIO pins 13(TX)/14(RX)
- **USB:** OTG cable, phone is USB Host

## GitHub
- Repo: twoskoops707/Pen15 (public)
- Branch: main
- Builds via GitHub Actions → releases APK
