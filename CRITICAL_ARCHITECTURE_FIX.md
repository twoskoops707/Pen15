# CRITICAL ARCHITECTURE FIX

## THE PROBLEM

**Flipper Companion App Architecture is FUNDAMENTALLY FLAWED for USB communication!**

### Why it can't work:
1. When you run a custom app on Flipper Zero, that app takes over the USB interface
2. The app can't listen on USB CDC serial because it's IN USE by the app itself
3. `FuriHalSerialIdUsb` doesn't exist in the SDK - only GPIO UART ports exist:
   - `FuriHalSerialIdUsart` - GPIO UART pins
   - `FuriHalSerialIdLpuart` - Low power UART pins

## THE SOLUTION

**Use Flipper's Built-in CLI directly - NO companion app needed!**

### How it works:
1. Flipper Zero has a built-in CLI that's ALWAYS accessible via USB CDC
2. When connected via USB, the Flipper automatically exposes a VirtualCOM port
3. Commands can be sent directly to the CLI without any custom app

### Built-in Flipper CLI Commands:
```
gpio set <pin> <0|1>     - Set GPIO pin high/low
gpio read <pin>          - Read GPIO pin state
rfid read                - Read RFID tag (requires RFID app to be open)
nfc detect               - Detect NFC cards
subghz rx <freq>         - Receive on frequency (requires SubGHz app)
subghz tx <freq> <file>  - Transmit saved signal
ir tx <file>             - Transmit IR signal
ibutton read             - Read iButton key
bt                       - Bluetooth commands
storage                  - File system operations
loader open <app>        - Launch Flipper apps remotely!
```

### The Android app should:
1. Connect to Flipper via USB CDC (VID:0x0483, PID:0x5740) ✓ ALREADY DOES THIS
2. Send CLI commands directly to the Flipper
3. Parse responses from Flipper CLI

### Example Flow:
```
Android sends: loader open SubGHz\r\n
Flipper: OK, SubGHz app launched

Android sends: subghz rx 433920000\r\n
Flipper: SubGHz receiver started

<user presses garage remote>

Flipper sends: Signal detected: Princeton, Code: 0x12ABC\r\n
Android parses and displays result
```

## WHAT TO DO NOW

### 1. Delete Flipper Companion App
**It's not needed and adds unnecessary complexity!**
- Remove `flipper_app/` directory
- Remove Flipper companion workflow
- Remove documentation about installing .fap files

### 2. Update Android App
**Use Flipper CLI commands directly:**

```kotlin
// Launch SubGHz app first
flipperUSB.sendCommand("loader open SubGHz")
delay(2000) // Wait for app to launch

// Then send SubGHz-specific commands
flipperUSB.sendCommand("subghz rx 433920000")
```

### 3. Update Brute Force Scripts
**Use Flipper CLI via USB CDC:**

```bash
# SubGHz brute force
for CODE in {0..1000}; do
    HEX_CODE=$(printf "%06X" $CODE)
    echo "loader open SubGHz" > /dev/ttyACM0
    sleep 0.5
    echo "subghz tx 433920000 /ext/subghz/brute/$HEX_CODE.sub" > /dev/ttyACM0
    sleep 0.2
done
```

### 4. Pre-create Signal Files
**For brute forcing, pre-generate .sub files:**
```python
# Generate Princeton protocol files
for code in range(0x000000, 0xFFFFFF):
    with open(f"/ext/subghz/brute/{code:06X}.sub", "w") as f:
        f.write(f"Filetype: Flipper SubGhz RAW File\n")
        f.write(f"Version: 1\n")
        f.write(f"Frequency: 433920000\n")
        f.write(f"Preset: FuriHalSubGhzPresetOok650Async\n")
        f.write(f"Protocol: Princeton\n")
        f.write(f"Bit: 24\n")
        f.write(f"Key: {code:06X}\n")
```

## BENEFITS OF THIS APPROACH

✅ **No custom Flipper app needed** - Uses built-in CLI
✅ **Works immediately** - No .fap installation required
✅ **More reliable** - Uses official Flipper APIs
✅ **Simpler codebase** - Remove entire flipper_app directory
✅ **Better compatibility** - Works with all Flipper firmwares
✅ **Can launch apps remotely** - `loader open <app>` command

## IMMEDIATE ACTIONS

1. Test if `loader open` command works via USB CDC
2. Document all available Flipper CLI commands
3. Update Android app to use CLI commands
4. Remove Flipper companion app entirely
5. Update all documentation and guides
6. Commit changes and rebuild Android app ONLY

## CLI COMMAND REFERENCE

### Loader (App Launcher)
```
loader open <app>    - Open app (SubGHz, RFID, NFC, etc.)
loader close         - Close current app
loader list          - List installed apps
```

### GPIO
```
gpio set <pin> <0|1>  - Set pin state
gpio read <pin>       - Read pin state
gpio mode <pin> <mode> - Set pin mode
```

### Storage
```
storage list <path>   - List files
storage read <file>   - Read file
storage write <file>  - Write file
storage remove <file> - Delete file
```

### System
```
info                  - System information
uptime                - System uptime
free                  - Memory usage
ps                    - Process list
```

## CONCLUSION

The architecture should be:
- **Android App** → USB CDC → **Flipper CLI** → **Built-in Apps**

NOT:
- Android App → USB CDC → Custom Companion App (IMPOSSIBLE)
- Android App → GPIO UART → Custom Companion App (requires wiring)

**The Flipper CLI is the OFFICIAL way to control Flipper remotely!**
