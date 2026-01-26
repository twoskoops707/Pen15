# Pen15 Project - CRITICAL SETTINGS (DO NOT CHANGE)

## USB Serial Communication - VERIFIED SETTINGS

### Flipper Zero USB CDC Serial
**Source:** https://docs.flipper.net/zero/development/cli

```
Baud Rate:  115200  (CONFIRMED - DO NOT CHANGE)
Data Bits:  8
Stop Bits:  1
Parity:     None
Flow:       None
DTR:        ENABLED (required for CDC-ACM)
RTS:        ENABLED
```

**Connection examples from official docs:**
- `minicom -D /dev/tty.usbmodemflip_xxx -b 115200`
- `tio -b 115200 /dev/cu.usbmodem1101`
- `putty.exe -serial COM3 -sercfg 115200,8,n,1,N`

### Flipper Zero USB IDs
```
VID: 0x0483 (STMicroelectronics)
PID: 0x5740 (Virtual COM Port)
```

## SerialInputOutputManager (usb-serial-for-android)

**CORRECT USAGE:**
```kotlin
// Create manager with listener and start (library handles threading)
ioManager = SerialInputOutputManager(port, listener)
ioManager?.start()  // This is CORRECT - library manages its own thread
```

**Note:** The library's API changed. `.start()` is now the correct method.
Do NOT try to use executor.submit() - SerialInputOutputManager is NOT a Runnable.

## Flipper CLI Commands (Verified Working)

| Feature | Command | Notes |
|---------|---------|-------|
| Help | `help` | List all commands |
| RFID Read | `rfid read` | Works |
| NFC | N/A | GUI only - removed from CLI |
| iButton | `ikey read` | NOT `ibutton read` |
| SubGHz RX | `subghz rx` | Works |
| IR Receive | `ir rx` | Works |
| Storage | `storage list /ext/` | Works |
| Device Info | `device_info` | Works |

## Build Rules

1. **ALWAYS use GitHub Actions** - Never build locally
2. **Batch changes** - Don't trigger builds for every small change
3. **Test before commit** - Verify code compiles

## Current Version
- v84+ with 115200 baud rate fix
- SerialInputOutputManager on executor
- Live data display via onNewData callback
