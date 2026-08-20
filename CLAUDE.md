# Pen15 Project - v3.0

## MANDATORY WORKING RULES — NEVER SKIP

1. **READ BEFORE TOUCHING.** Before editing ANY file, read its current contents in full. No exceptions.
2. **NEVER GUESS.** If you don't know what a file contains, read it. If you don't know what a class does, grep it.
3. **RESEARCH FIRST, CODE SECOND.** Every change starts with reading the actual current state of the code.
4. **NO ASSUMPTIONS.** Just because something was written in a previous session doesn't mean it's correct. Read it anyway.
5. **WHEN IN DOUBT, READ MORE FILES.** Use Read, Grep, Glob tools exhaustively before proposing any change.

Violating these rules wastes the user's time and money. Do not do it.

## Branches
| Branch | Purpose | Status |
|--------|---------|--------|
| main | Stable release | v1.0.129 |
| dolphin-rewrite | Review + rewrite with dolphin-mistral model | Active - primary dev |
| recon-tools | OSINT, Google Dork, Phone Sensors, Termux fixes | Active |
| awok-only | Direct AWOK/ESP32 USB serial (no Termux dependency) | Active |

## Active Branch: dolphin-rewrite
- Branch created from fap-patch for code review and rewrite
- GitHub Actions workflow: Emergency Build & Release (APK only, no FAP due to token scope)
- Build: GitHub Actions run 24451615544 (in progress)
- All 47 .kt files compile clean (only deprecation warnings, zero errors)
- btnScrollLock wired in ESP32ManagerActivity (auto-scroll toggle) — fixed in d9dcce2
- FAP build pending: needs `workflow` scope to push workflow changes
- APK download: https://github.com/twoskoops707/Pen15/releases/tag/build-202

## USB Serial Communication

### Flipper Zero USB CDC Serial
```
Baud Rate:  115200
Data Bits:  8
Stop Bits:  1
Parity:     None
Flow:       None
DTR:        ENABLED (required for CDC-ACM)
RTS:        ENABLED
```

### Flipper Zero USB IDs
```
Stock:      VID 0x0483 (STMicroelectronics) PID 0x5740 (Virtual COM Port)
Momentum:   VID/PID can be spoofed to arbitrary values
```

### Momentum Firmware (mntm-014)
- Flipper updated from Unleashed to Momentum firmware
- Can spoof USB VID/PID - breaks hardcoded detection
- FlipperUSBManager uses 3-tier fallback:
  1. Stock VID/PID match (0x0483/0x5740)
  2. Any default prober driver
  3. CdcAcmSerialDriver on ALL USB devices
- device_filter.xml includes VID-only matches and common spoofed VIDs

### ESP32/AWOK USB IDs
```
CP2102:     VID 0x10C4
CH340:      VID 0x1A86
Espressif:  VID 0x303A
FTDI:       VID 0x0403
```

### ESP32 Marauder CLI Commands (AWOK Dual Mini v3)
```
scanall             - Scan WiFi APs and stations
stopscan            - Stop current scan
select -a <idx>     - Select AP by index
attack -t deauth    - Deauth attack on selected AP
sniffpmkid -d -l    - Targeted PMKID capture (deauth + list)
sniffbt             - Bluetooth sniffing
blespam -t all      - BLE spam attack
evilportal -c start - Evil twin portal
channel -s <n>      - Set WiFi channel
list -a             - List scan results (APs)
clearlist -a        - Clear results
gps                 - GPS info (if module present)
help                - List commands
reboot              - Reboot ESP32
```

## Architecture (v2.0 - awok-only branch)

### Core Serial Managers
- **FlipperUSBManager.kt** - Flipper Zero USB serial with Momentum firmware support
- **ESP32SerialManager.kt** - Direct AWOK/ESP32 USB serial (bypasses Termux)
- **FlipperConnectionManager.kt** - Singleton connection state manager

### Activity Modules
- **MainActivity.kt** - Dashboard with Flipper + ESP32 dual detection
- **ESP32ManagerActivity.kt** - Direct Marauder commands via USB serial
- **WiFiDeauthActivity.kt** - WiFi scan/deauth via ESP32 serial (no Termux)
- **SubGHzActivity.kt** - SubGHz via Flipper CLI
- **RFIDActivity.kt** - RFID via Flipper CLI
- **NFCActivity.kt** - NFC via Flipper CLI
- **InfraredActivity.kt** - IR via Flipper CLI
- **GPIOActivity.kt** - GPIO via Flipper CLI
- **BadUSBActivity.kt** - BadUSB via Flipper CLI
- **IButtonActivity.kt** - iButton via Flipper CLI
- **OSINTActivity.kt** - OSINT tools via Termux
- **GoogleDorkActivity.kt** - Google dork query builder
- **NetworkScannerActivity.kt** - Network scanning via Termux
- **ExploitDatabaseActivity.kt** - Exploit DB search
- **PhoneSensorsActivity.kt** - Phone WiFi/BLE/NFC sensors
- **PacketSnifferActivity.kt** - Packet capture
- **HashCrackerActivity.kt** - Hash cracking
- **SettingsActivity.kt** - App settings

### Support Classes
- **TermuxIntegration.kt** - Termux command builders (OSINT, network, hash, WiFi)
- **ProcessManager.kt** - Inline script execution (no file writes to scoped storage)
- **ParameterDialog.kt** - Auto-discovery parameter dialog

### Key Design Decisions
- ESP32 communication uses usb-serial-for-android (mik3y v3.9.0) directly
- No Termux dependency for AWOK commands (was causing Permission Denied code 126)
- ProcessManager passes scripts inline to avoid Android scoped storage issues
- Flipper modules require Flipper connection; ESP32/tool cards do not

## Flipper CLI Commands (Verified Working)

| Feature | Command | Notes |
|---------|---------|-------|
| Help | `help` | List all commands |
| RFID Read | `rfid read` | NOT `lfrfid read` |
| NFC | `nfc field on/off` | Read/detect via GUI only |
| iButton | `ikey read` | NOT `ibutton read` |
| SubGHz RX | `subghz rx <freq>` | e.g., `subghz rx 433920000` |
| SubGHz TX | `subghz tx_from_file <path>` | Play .sub file |
| IR Receive | `ir rx` | Works |
| IR Transmit | `ir tx <protocol> <addr> <cmd>` | Works |
| Storage | `storage list /ext/` | Works |
| Device Info | `device_info` | Key format: `key : value` |
| Power 5V | `power 5v 1` / `power 5v 0` | Enable/disable |
| Reboot | `power reboot` | Reboots Flipper |
| Shutdown | `power off` | Powers off |

## Build Rules

1. **ALWAYS use GitHub Actions** - Never build locally
2. **Batch changes** - Don't trigger builds for every small change
3. **Test before commit** - Verify code compiles

## Termux Limitations on Android 11
- `/tmp` is read-only - can't create temp files there
- `getExternalFilesDir()` inaccessible to Termux (scoped storage)
- Scripts must be passed inline, not written to files
- `gh` CLI crashes on Termux ARM64 (SIGSYS: bad system call)
- Use `$HOME/.pen15/` for PID/output storage

## Recent Session History (awok-only branch)
1. Fixed Termux Permission Denied (code 126) - ProcessManager inline scripts
2. Created awok-only branch from recon-tools
3. Created ESP32SerialManager.kt for direct AWOK USB serial
4. Rewrote ESP32ManagerActivity + WiFiDeauthActivity to use direct serial
5. Fixed FlipperUSBManager for Momentum firmware VID/PID spoofing
6. Updated device_filter.xml with broader VID matching

## Next Steps
- Test Momentum firmware connection (check `adb logcat | grep FlipperUSBManager`)
- Verify ESP32 serial commands work end-to-end on device
- Merge awok-only improvements back to main when stable
