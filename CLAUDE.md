# Pen15 Project - v4.0 "Pentesting for Dummies"

## 🎯 Firmware Recommendation

**RECOMMENDED: Momentum Firmware**
- **Why**: Perfect balance of stability + essential pentesting tools
- **Install**: https://github.com/Next-Flip/Momentum-Firmware
- **Why not RogueMaster**: Bloated, unstable, too many unnecessary apps
- **Momentum advantages**: 
  - WiFi Marauder v0.7.8 built-in
  - Stable and actively maintained
  - Essential apps only (no bloat)
  - Works great with Awok boards

## 🔌 Flipper Zero USB Communication Specs

### USB Serial Settings
```
Baud Rate:  230400 (NOT 115200!)
Data Bits:  8
Stop Bits:  1
Parity:     None
Flow:       None
DTR:        ENABLED (required for CDC-ACM)
RTS:        ENABLED
```

### USB IDs
```
VID: 0x0483 (STMicroelectronics)
PID: 0x5740 (Virtual COM Port)
```

## 📟 Correct CLI Commands (Momentum Firmware)

### RFID (125 kHz)
| Feature | Command | Notes |
|---------|---------|-------|
| Read | `rfid read` | Waits for card, returns Key/Protocol |
| Detect | `rfid detect` | Quick field test |
| Emulate | `rfid emulate <key>` | Emulate read card |

### NFC (13.56 MHz)
| Feature | Command | Notes |
|---------|---------|-------|
| Detect | `nfc detect` | Detects field presence |
| Read | `nfc read` | Full card read |
| Emulate | `nfc emulate <uid>` | Emulate UID |
| Field On | `nfc field on` | Activate antenna |
| Field Off | `nfc field off` | Deactivate antenna |

### SubGHz
| Feature | Command | Notes |
|---------|---------|-------|
| RX Start | `subghz rx <freq_hz>` | Start receiving |
| RX End | `subghz rx_end` | Stop receiving |
| TX File | `subghz tx_from_file <path>` | Transmit saved signal |
| TX Raw | `subghz tx <freq_hz> <data>` | Transmit raw |

### iButton
| Feature | Command | Notes |
|---------|---------|-------|
| Read | `ibutton read` | Read Dallas key |
| Emulate | `ibutton emulate <key>` | Emulate key |

### Infrared
| Feature | Command | Notes |
|---------|---------|-------|
| Receive | `ir rx` | Learn IR signal |
| Transmit | `ir tx <protocol> <addr> <cmd>` | Send IR command |

### GPIO / Awok Board
| Feature | Command | Notes |
|---------|---------|-------|
| Set Mode | `gpio mode <pin> <0/1>` | 0=input, 1=output |
| Read | `gpio read <pin>` | Read pin state |
| Write | `gpio write <pin> <0/1>` | Set pin high/low |
| UART Bridge | `gpio uart_tx` | Enable UART bridge for Marauder |

### System
| Feature | Command | Notes |
|---------|---------|-------|
| Info | `device_info` | Get device info |
| Storage | `storage list /ext` | List SD card |
| Power 5V | `power 5v 1/0` | Toggle 5V output |
| Reboot | `power reboot` | Reboot Flipper |
| Off | `power off` | Power down |

## 🧙 Wizard System

The app now uses a step-by-step wizard approach:

1. **What?** - Choose your goal (clone, copy, test)
2. **Position** - Visual guide for card/device placement
3. **Execute** - One-tap execution with real-time feedback

### Wizard Types
- **RFIDWizard** - Card reading with positioning help
- **NFCWizard** - NFC tag reading and writing
- **SubGHzWizard** - Signal capture and replay
- **OSINTWizard** - Google dorking with pre-built queries

## 🏗️ Awok Board Integration

### GPIO Pinout (Flipper to Awok ESP32)
```
Flipper Pin 7  (3.3V)  → ESP32 3.3V
Flipper Pin 8  (GND)   → ESP32 GND
Flipper Pin 15 (RX)    → ESP32 TX (GPIO1)
Flipper Pin 16 (TX)    → ESP32 RX (GPIO3)
Flipper Pin 17 (CTS)   → ESP32 RTS
Flipper Pin 18 (RTS)   → ESP32 CTS
```

### Using Marauder via Flipper
1. Connect Awok board to Flipper GPIO
2. Enable UART bridge: `gpio uart_tx`
3. Send Marauder commands via serial
4. Commands are forwarded to ESP32 automatically

## 📱 App Architecture

```
MainActivity (Dashboard)
├── WizardLauncher (Routes to specific wizards)
│   ├── RFIDWizardActivity
│   ├── NFCWizardActivity
│   ├── SubGHzWizardActivity
│   ├── InfraredWizardActivity
│   ├── WiFiWizardActivity (for Awok/Marauder)
│   ├── BluetoothWizardActivity
│   └── OSINTWizardActivity
├── FlipperConnectionManager
│   ├── FlipperUSBManager (USB-C serial)
│   └── FlipperBluetoothManager (BLE)
└── SettingsActivity
```

## 🔧 Build Notes

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

## ⚠️ Common Issues & Fixes

### Not connecting via USB
- Check baud rate: must be 230400 (not 115200)
- DTR/RTS must be enabled
- Try different USB cable
- Check Flipper is in CLI mode (hold ← when booting)

### Commands not working
- Verify Momentum firmware installed
- Check CLI commands match firmware version
- Try `help` command first to verify connection

### Awok board not responding
- Verify GPIO wiring correct
- Enable UART bridge: `gpio uart_tx`
- Check ESP32 has Marauder firmware flashed

## 🎨 UI Design Principles

1. **One Goal Per Screen** - Don't overwhelm users
2. **Visual Guides** - Show where to place cards/devices
3. **Console Feedback** - Real-time command output
4. **Progressive Disclosure** - Simple → Advanced options
5. **Error Prevention** - Validate before executing

## 📋 Roadmap

- [x] Fix USB communication (230400 baud, DTR/RTS)
- [x] Wizard-based UI
- [x] RFID/NFC/SubGHz wizards
- [x] OSINT/Google dorking
- [ ] WiFi Marauder integration
- [ ] Signal library management
- [ ] Automated attacks
- [ ] Report generation
