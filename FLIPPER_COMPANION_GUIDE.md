# Flipper Zero Companion App Installation Guide

This guide explains how to compile and install the Flipper Zero companion app that enables two-way communication with the Android Pentesting Dashboard app.

## What It Does

The companion app running on Flipper Zero allows the Android app to:
- Execute SubGHz commands remotely (frequency scanning, signal capture, replay)
- Read RFID cards and send data back to phone
- Read NFC tags
- Control GPIO pins
- Execute BadUSB scripts
- iButton operations

## Requirements

- **Flipper Zero** device
- **ufbt** (Micro Flipper Build Tool) installed
- **USB cable** for flashing

## Installation Steps

### 1. Install ufbt

```bash
# On Linux/Mac
pip3 install --upgrade ufbt

# On Windows
python -m pip install --upgrade ufbt
```

### 2. Clone This Repository

```bash
git clone https://github.com/twoskoops707/Pen15.git
cd Pen15/flipper_app
```

### 3. Build the FAP File

```bash
ufbt
```

This will create `pentest_companion.fap` in the `dist` directory.

### 4. Install to Flipper Zero

**Method 1: Via USB**
```bash
ufbt launch
```

**Method 2: Via qFlipper**
1. Connect Flipper Zero to computer
2. Open qFlipper
3. Navigate to `/apps/Tools/`
4. Copy `pentest_companion.fap` to this folder

**Method 3: Via SD Card**
1. Remove SD card from Flipper Zero
2. Copy `pentest_companion.fap` to `/apps/Tools/` on SD card
3. Reinsert SD card into Flipper Zero

### 5. Run on Flipper Zero

1. Navigate to: **Apps → Tools → Pentest Companion**
2. The app will start and listen for commands from Android app

## Usage

### From Android App

1. **Connect Flipper Zero**:
   - Via Bluetooth: Pair in Android Settings
   - Via USB-C: Use OTG adapter

2. **Send Commands**:
   The Android app will send commands like:
   ```
   subghz rx 433920000
   rfid read
   nfc read
   gpio set 5 high
   ```

3. **Receive Responses**:
   Flipper Zero sends back real-time data:
   ```
   SubGHz signal detected at 433.92MHz
   RFID: EM4100 ID: 1234567890
   NFC: MIFARE Classic 1K
   ```

## Available Commands

### SubGHz Commands
```
subghz rx <frequency>    - Start receiving on frequency
subghz tx <frequency>    - Transmit on frequency
subghz scan              - Scan all frequencies
```

### RFID Commands
```
rfid read                - Read RFID card
rfid emulate <id>        - Emulate RFID card
```

### NFC Commands
```
nfc read                 - Read NFC tag
nfc emulate <type>       - Emulate NFC tag
```

### GPIO Commands
```
gpio set <pin> <high|low>  - Set GPIO pin state
gpio read <pin>             - Read GPIO pin state
```

## Troubleshooting

### App Not Appearing in Flipper Menu
- Make sure the .fap file is in `/apps/Tools/`
- Reboot Flipper Zero
- Try rebuilding with latest ufbt

### Can't Connect from Android
- Check Bluetooth pairing
- Try USB-C connection instead
- Ensure Flipper app is running
- Check permissions in Android app

### Commands Not Working
- Verify Flipper firmware is up to date
- Check command syntax
- Look at Flipper screen for error messages

## Development

### Building from Source

```bash
cd flipper_app
ufbt -d  # Debug build
ufbt -r  # Release build
```

### Adding New Commands

Edit `pentest_companion.c` and add new CLI handlers:

```c
void my_custom_command(Cli* cli, FuriString* args, void* context) {
    // Your code here
    printf("Custom command executed!\r\n");
}

// Register in pentest_companion_cli_init():
cli_add_command(cli, "mycmd", CliCommandFlagDefault, my_custom_command, NULL);
```

Then rebuild and flash to Flipper.

## Security Notice

This tool is for **AUTHORIZED PENETRATION TESTING ONLY**.

- Only use on networks/systems you own
- Only use on devices you have permission to test
- Unauthorized use may be illegal in your jurisdiction
- Use responsibly and ethically

## Support

For issues, questions, or feature requests:
- Open an issue on GitHub
- Check Flipper Zero documentation: https://docs.flipper.net/
- Join Flipper Zero Discord community

##  License

This project is for educational and authorized security testing purposes only.
