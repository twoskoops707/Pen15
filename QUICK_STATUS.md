# QUICK STATUS - v84

## Current Build
v84 pushed - USB Serial Communication Fix

## CRITICAL USB SETTINGS (DO NOT CHANGE)
```
Baud Rate:  115200  ← VERIFIED CORRECT
Data Bits:  8
Stop Bits:  1
Parity:     None
DTR:        true
RTS:        true
```

**Source:** https://docs.flipper.net/zero/development/cli

## What's Fixed in v84
1. ✅ Baud rate: 115200 (was wrongly 230400)
2. ✅ SerialInputOutputManager on executor thread
3. ✅ Live data display in terminal
4. ✅ Terminal inside scroll view (no blocking)
5. ✅ Simplified command sending

## Test Instructions
1. Connect Flipper Zero via USB-C OTG
2. Open app, tap "Connect Flipper"
3. Should show "USB" status and port info
4. Tap "Test CLI" - should show Flipper response
5. Try other buttons (RFID Read, NFC Detect, etc.)

## Flipper CLI Commands
| Button | Command |
|--------|---------|
| Test CLI | `help` |
| RFID Read | `rfid read` |
| NFC Detect | `nfc detect` |
| SubGHz RX | `subghz rx` |
| iButton | `ikey read` |
| IR | `ir rx` |

## Files
- Settings: `CLAUDE.md` (CRITICAL - DO NOT IGNORE)
- Memory: `PROJECT_MEMORY.md`
- This: `QUICK_STATUS.md`
