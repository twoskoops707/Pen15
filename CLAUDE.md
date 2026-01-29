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

## Flipper CLI Commands (Verified Working - Unleashed 084e)

Available commands from `help`:
```
bt loader crypto js nfc exit buzzer input neofetch
echo log onewire device_info free_blocks top factory_reset
uptime ? storage vibro ikey ! start_rpc_session power
subshell_demo i2c subghz ir update reload_ext_cmds hello_world
rfid sleep help sysctl info gpio date led free
```

| Feature | Command | Notes |
|---------|---------|-------|
| Help | `help` | List all commands |
| RFID Read | `rfid read` | NOT `lfrfid read` — `lfrfid` doesn't exist in CLI |
| NFC | `nfc field on/off` | Read/detect via GUI only |
| iButton | `ikey read` | NOT `ibutton read` |
| SubGHz RX | `subghz rx <freq>` | e.g., `subghz rx 433920000` |
| IR Receive | `ir rx` | Works |
| Storage | `storage list /ext/` | Works |
| Device Info | `device_info` | Key format: `key_name                : value` |
| Power 5V | `power 5v 1` / `power 5v 0` | Enable/disable 5V external |
| Reboot | `power reboot` | Reboots Flipper |
| Shutdown | `power off` | Powers off |
| GPIO | `gpio mode` | GPIO pin info |

### device_info Output Format
```
hardware_model                : Flipper Zero
hardware_name                 : Scoops
firmware_version              : unlshd-084e
firmware_build_date           : 13-12-2025
firmware_origin_fork          : Unleashed
radio_ble_mac                 : 53636F26E180
```
Keys use underscores, values padded with spaces before ` : `.

### Power Commands
`power` only supports: `off`, `reboot`, `reboot2dfu`, `5v <0 or 1>`.
There is NO `power info` or `power otg` command.

## Build Rules

1. **ALWAYS use GitHub Actions** - Never build locally
2. **Batch changes** - Don't trigger builds for every small change
3. **Test before commit** - Verify code compiles

## Current Version
- v106 with result parsing, AWOK Marauder menu
- Single-activity architecture (MainActivity.kt only)
- SerialInputOutputManager.start() — library manages own thread
- Live data display via onNewData callback + output buffering for parsed results
