# Flipper Zero Companion App

This is the Flipper Zero side application that works with the Android Pentest Dashboard app.

## What it does

- Receives commands from Android app via USB serial CLI
- Executes SubGHz, RFID, NFC, GPIO operations
- Sends results back to Android app

## Building

### Requirements
- Flipper Zero Unleashed or Official firmware
- ufbt (Flipper Build Tool)

### Build Instructions

```bash
# Install ufbt
pip install ufbt

# Build the app
cd flipper_app
ufbt

# The .fap file will be in dist/

# Install to Flipper
ufbt launch
```

### Manual Install

1. Copy `pentest_companion.fap` to Flipper's SD card `/ext/apps/Tools/` folder
2. Run the app from Flipper menu: Apps -> Tools -> Pentest Companion

## How it works

The Android app sends commands via USB serial like:
```
subghz_rx 433920000
rfid_read
nfc_read
```

The Flipper app registers these as CLI commands and executes them, sending results back.

## Commands

| Command | Description |
|---------|-------------|
| `subghz_rx <freq>` | Start SubGHz receive on frequency |
| `subghz_tx <data>` | Transmit SubGHz signal |
| `rfid_read` | Read RFID 125kHz card |
| `nfc_read` | Read NFC 13.56MHz card |
| `gpio_set <pin> <value>` | Set GPIO pin |

## Development

To add more commands, edit `pentest_companion.c` and add:
1. Command handler function
2. Register in `pentest_companion_cli_init()`

## License

For authorized security testing only.
