# PEN15 — Pentest Dashboard

**Phone-controlled Flipper Zero + ESP32 Marauder pentesting platform**

A Kotlin Android app that controls your Flipper Zero and ESP32 hardware via USB, with background Termux/Linux tools for cracking and exploitation.

## Architecture

```
┌──────────────┐     USB-C      ┌───────────────┐    GPIO UART     ┌──────────────┐
│  Android App │ ──────────────► │ Flipper Zero  │ ──────────────► │ ESP32        │
│  (PEN15)     │  CDC Serial    │ Pen15 FAP     │  Pins 13/14     │ Marauder FW  │
│              │  115200 baud   │ (JSON bridge) │  115200 baud    │ (WiFi/BLE)   │
└──────┬───────┘                └───────────────┘                 └──────────────┘
       │
       │ Termux RUN_COMMAND
       ▼
┌──────────────┐
│ Termux       │
│ Linux tools: │
│ • aircrack-ng│
│ • hashcat    │
│ • nmap       │
│ • sherlock   │
│ • nikto      │
└──────────────┘
```

## How It Works

### Layer 1: Phone ↔ Flipper Zero (USB CDC Serial)
- Phone connects to Flipper via USB-C cable
- Flipper exposes a virtual COM port (STM32 VID 0x0483)
- The **Pen15 Controller FAP** runs on the Flipper, listening on CDC channel 0
- Communication uses a **JSON protocol** over serial:
  - Phone sends: `{"action":"rfid_read","id":"abc123"}`
  - FAP responds: `{"status":"ok","type":"EM4100","data":"AABBCCDD","id":"abc123"}`

### Layer 2: Flipper Zero → ESP32 Marauder (GPIO UART Bridge)
- ESP32 connects to Flipper's GPIO header (pins 13/14) at 115200 baud
- The FAP enters **bridge mode**: transparently forwards bytes between USB and UART
- Phone sends Marauder CLI commands through the bridge:
  - `scanap` → scan WiFi networks
  - `attack -t deauth` → deauthentication attack
  - `sniffpmkid -d -l` → targeted PMKID capture
  - `evilportal -c start` → captive portal attack

### Layer 3: Phone → Termux (Background Linux Tools)
- For tasks the ESP32 can't do alone (hash cracking, network scanning, OSINT)
- Scripts run in background Termux sessions
- Output is polled and displayed in-app

## Hardware Requirements

| Hardware | Purpose | Connection |
|----------|---------|------------|
| Android phone (11+) | Run PEN15 app | — |
| Flipper Zero | Control RFID/NFC/SubGHz/IR/iButton/GPIO | USB-C to phone |
| AWOK Dual Mini v3 (or ESP32 dev board) | WiFi attacks (deauth, PMKID, evil portal) | GPIO to Flipper |

## Flashing the ESP32 with Marauder

1. Download Marauder firmware from [ESP32Marauder releases](https://github.com/justcallmekoko/ESP32Marauder/releases)
2. Flash using one of:
   - [SpaceHuhn Web Installer](https://spacehuhn-tech.github.io/esp8266_deauther/docs/install/)
   - [FZ Marauder Flasher](https://github.com/0xchocolate/flipperzero-esp-flasher)
   - Build from source: `cd ESP32Marauder && platformio run -t upload`

## Building the Flipper FAP

The Pen15 Controller FAP runs ON the Flipper Zero itself. Build it with [ufbt](https://github.com/aspect-build/ufbt):

```bash
# Install ufbt
pip install --upgrade ufbt

# Setup for your firmware channel
ufbt update --channel=dev    # Momentum/Unleashed
# or
ufbt update --channel=release  # Official

# Build
cd fap/pen15_controller
ufbt

# Deploy to Flipper via USB
ufbt launch
```

Or use the convenience script:
```bash
cd fap
./build.sh deploy
```

## Building the Android APK

### Option A: GitHub Actions (Recommended)
```bash
git push
# APK available at: Actions → latest run → Artifacts
```

### Option B: Local
```bash
# Requires Android Studio or Android SDK with build-tools 34
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Marauder Command Reference

These commands are sent from the app to the ESP32 through the Flipper bridge:

### WiFi Attacks
| Command | Description |
|---------|-------------|
| `scanap` | Scan for WiFi access points |
| `scansta` | Scan for connected stations (clients) |
| `list -a` | List discovered APs |
| `list -c` | List discovered stations |
| `select -a <index>` | Select target AP(s) — comma-separated for multiple |
| `select -c <index>` | Select target station(s) |
| `attack -t deauth` | Deauth flood against selected APs |
| `attack -t deauth -c` | Targeted deauth against selected stations |
| `sniffpmkid` | Passive PMKID sniff |
| `sniffpmkid -d -l` | Active targeted PMKID sniff with channel hopping |
| `stopscan` | Stop any running scan/attack |

### Evil Portal
| Command | Description |
|---------|-------------|
| `ssid -a -n <name>` | Add an SSID to the AP name list |
| `evilportal -c start` | Start captive portal with selected AP config |
| `evilportal -c stop` | Stop the portal |
| `evilportal -c sethtml <file>` | Select HTML template from SD card |

**Note:** Evil Portal requires an SD card on the ESP32 (since Marauder v0.11.0). Credentials are saved to `evil_portal_x.log` on the SD card root.

### Bluetooth
| Command | Description |
|---------|-------------|
| `sniffbt` | Bluetooth sniffer |
| `sourapple` | Apple BLE spam |
| `swiftpair` | Microsoft Swift Pair spam |

### Signal Monitoring
| Command | Description |
|---------|-------------|
| `sigmon` | Signal strength monitor for selected AP |

## Termux Setup

1. Install **Termux from F-Droid** (NOT Google Play Store)
2. Open Termux and run:
   ```bash
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```
3. Grant Files and Media permission to Termux in Android Settings
4. Open PEN15 app → Settings → Install Tools

## WiFi Capture → Crack Workflow

1. **Connect** AWOK to Flipper via GPIO (or connect AWOK directly via USB)
2. **Open** WiFi Deauth activity → tap START SCAN
3. **Select** target network from the list
4. **Tap** CAPTURE PMKID → sends `sniffpmkid -d -l` to Marauder
5. **Wait** for PMKID hash to appear in the log
6. **Copy** the hash → Open Hash Cracker activity
7. **Paste** hash → select hashcat mode 22000 → tap CRACK
8. **Password** appears when cracked

## Security

- All user input is sanitized via `InputValidator.shellSafe()` before injection into shell commands
- Type-specific validators (IP, MAC, email, hash) prevent malformed input
- Termux commands use `RUN_COMMAND` permission with background sessions
- Session logs are stored in app-private storage

## Legal

**For authorized testing only.** Only use on networks and devices you own or have written permission to test. Unauthorized access to computer systems is a criminal offense.
