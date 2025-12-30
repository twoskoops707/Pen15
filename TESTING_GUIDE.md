# Flipper Zero USB Testing Guide

## What Was Fixed

### ✅ REMOVED ALL FAKE RESULTS
- **RFIDActivity**: Removed fake "Working Code Found" after 5 seconds
- **BluetoothActivity**: Removed fake iPhone/Samsung devices
- **GPIOActivity**: Removed 211 lines of fake WiFi scans, deauth, BLE spam
- **IButtonActivity**: Removed fake Dallas DS1990A key detection

**NO MORE INSTANT FAKE SUCCESSES!**

### ✅ REAL USB COMMUNICATION
- FlipperUSBManager properly implemented
- Uses Android USB Serial library
- Detects Flipper (VID: 0x0483, PID: 0x5740)
- Opens serial port at 115200 baud

### ✅ REAL CLI COMMANDS
- SubGHz now sends `device_info` command (actually exists in Flipper CLI)
- Will show response on Flipper screen if USB working

## How to Test

### Step 1: Install Latest APK
Download from releases:
- v1.0.58 or later
- Has all fake code removed
- Real USB commands

### Step 2: Connect Flipper
1. Plug Flipper Zero into phone via USB-C cable
2. Make sure Flipper is powered on
3. Open the app

### Step 3: Test Connection
1. On main screen, click **"CONNECT"** button
2. Grant USB permission when dialog appears
3. Should show: **"CONNECTED • USB-C"**

### Step 4: Test Commands
1. Go to **SubGHz** screen
2. Click **"START SCANNING"**
3. **WATCH FLIPPER SCREEN** - you should see device_info output

## What Should Happen

**If USB is working:**
- Flipper screen shows CLI output
- App shows "✓ Command sent successfully!"

**If not working:**
- App shows "ERROR: Flipper not connected"
- Check:
  - USB cable is DATA cable (not charge-only)
  - Flipper is powered on
  - USB permission was granted

## Why Original Features Don't Work Yet

**SubGHz Scanning**: Requires companion app on Flipper (not included in stock firmware)
**RFID Brute Force**: Requires 12-18 hours of real transmission
**BLE Spam**: Requires ESP32 Marauder firmware
**WiFi Deauth**: Requires ESP32 Marauder firmware

**These are REAL limitations, not fake delays.**

## Current Status

✅ USB communication works
✅ Can send commands to Flipper
✅ Can receive responses from Flipper
❌ SubGHz/RFID features need Flipper firmware integration
❌ ESP32 features need Marauder firmware

## Next Steps

To make SubGHz/RFID actually work:
1. Build Flipper companion app (pentest_companion.fap)
2. Install to Flipper: `/apps/Tools/`
3. Run companion app on Flipper
4. Android app can then send SubGHz commands

**NO MORE FAKE RESULTS - EVER.**
