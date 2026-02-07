# Pen15 Project - v2.0

## USB Serial Communication

### Flipper Zero USB CDC Serial
```
Baud Rate:  230400
Data Bits:  8
Stop Bits:  1
Parity:     None
Flow:       None
DTR:        ENABLED (required for CDC-ACM)
RTS:        ENABLED
```

### Flipper Zero USB IDs
```
VID: 0x0483 (STMicroelectronics)
PID: 0x5740 (Virtual COM Port)
```

## Architecture (v2.0)
- Tab-based navigation: Terminal, Flipper, WiFi, Status
- FlipperSerial.kt handles all USB serial communication
- AppState ViewModel shares state across fragments
- Material3 dark theme
- SubGHz brute force generator (CAME, NICE, Linear, Chamberlain, Holtek, Ansonic)
- WPA2 PMKID cracker (in-app PBKDF2-SHA1)
- Termux integration for aircrack-ng/hashcat

## Flipper CLI Commands (Verified Working - Unleashed 084e)

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
