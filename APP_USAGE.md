# Pen15 Master Pentest Dashboard - User Guide

## Quick Start

### Initial Setup
1. Install APK on Android device
2. Install Termux from F-Droid (NOT Google Play)
3. Grant all permissions when prompted

### Hardware Connections

#### Flipper Zero
**USB Connection:**
- Connect Flipper via USB-C cable
- On Flipper: `GPIO → USB-UART Bridge → Channel 0`
- Grant USB permission in Android

**Bluetooth Connection:**
- Pair Flipper in Android Bluetooth settings
- App will auto-connect

#### AWOK Mini V3 / ESP32 Marauder
- Connect via USB-C cable OR
- Connect to Flipper GPIO pins
- Grant USB permission

---

## Features & How to Use

### ✅ WORKING FEATURES

#### 1. WiFi Capture + Crack
**What it does:** Automated WiFi password cracking
**Steps:**
1. Tap "WiFi Capture + Crack"
2. Tap "CONFIGURE ATTACK"
3. Enter target SSID
4. Select wordlist (common-wifi recommended)
5. Tap "START CAPTURE + CRACK"
6. **Output shows IN APP** - watch progress live
7. Password appears when cracked

**Output shows:**
- Monitor mode enabling
- Network scanning
- Handshake capture
- Deauth packet sending
- Cracking progress
- Final password (if found)

#### 2. Hash Cracking
**What it does:** Crack password hashes
**Steps:**
1. Tap "Hash Cracker"
2. Paste hash into text box
3. Select hash type (MD5, SHA256, etc.)
4. Select wordlist
5. Tap "CRACK WITH HASHCAT" or "CRACK WITH JOHN"
6. Check Termux for progress (app shows process ID)

**Note:** Large wordlists take time (rockyou = hours)

#### 3. WiFi Deauth Attack
**What it does:** Disconnect devices from WiFi
**Steps:**
1. Tap "WiFi Deauth"
2. Tap "CONFIGURE ATTACK"
3. Enter target SSID and BSSID
4. Set deauth packet count (20-50 recommended)
5. Tap "START DEAUTH"
6. Devices disconnect immediately

#### 4. RFID Brute Force (Flipper Required)
**What it does:** Try all RFID codes
**Steps:**
1. Connect Flipper Zero
2. Tap "RFID" → "Brute Force"
3. Enter start/end ID range (hex)
4. Set delay (100ms recommended)
5. Hold Flipper near reader
6. Tap "START"
7. Press STOP to halt

**Process tracked:** Shows process ID, can stop anytime

#### 5. SubGHz Brute Force (Flipper Required)
**What it does:** Try all SubGHz remote codes
**Steps:**
1. Connect Flipper Zero
2. Tap "SubGHz" → "Brute Force"
3. Enter frequency (315MHz or 433.92MHz)
4. Enter code range
5. Tap "START"
6. Press STOP to halt

#### 6. NFC/RFID Reading (Flipper Required)
**What it does:** Read NFC tags and RFID cards
**Steps:**
1. Connect Flipper Zero
2. Tap "NFC" or "RFID"
3. Tap "READ"
4. Place card near Flipper
5. **Card data appears in app** (not "check Flipper")

**Shows:** Real UID/ID from hardware

#### 7. Packet Sniffing
**What it does:** Capture network packets
**Steps:**
1. Tap "Packet Sniffer"
2. Enter interface (wlan0)
3. Tap "START CAPTURE"
4. Let it run
5. Tap "STOP"
6. Tap "ANALYZE" to extract credentials

#### 8. ESP32 Marauder (AWOK Required)
**What it does:** WiFi/BLE attacks via ESP32
**Steps:**
1. Connect AWOK Mini V3
2. Tap "ESP32 Manager"
3. Tap "CONNECT" to detect device
4. Use "SCAN NETWORKS" or "BLE SCAN"
5. Check Termux or ESP32 screen for results

---

## Important Notes

### Process Management
- **STOP buttons work!** - Click to halt any running process
- **Switching activities** automatically stops previous process
- **Process IDs** shown in logs for tracking

### Output Display
- **WiFi Capture + Crack:** Shows output IN APP (live updates)
- **Hash Cracking:** Shows process ID, check Termux for progress
- **Flipper commands:** Real responses shown in app
- **RFID/NFC:** Real card data displayed

### Troubleshooting

**"No devices found":**
- Run `~/Pen15/usb_detect.sh` to diagnose
- Check USB permissions
- Try different cable

**"Check Termux":**
- Swipe from left edge → Termux
- Output appears there for some tools

**Process won't stop:**
- Use STOP button
- Switch to different activity (auto-stops)
- Check process ID in output

**WiFi attacks fail:**
- Need root OR Termux packages (aircrack-ng, etc.)
- Install: `pkg install aircrack-ng`

---

## Recommended Workflow

### Cracking WiFi Password:
1. WiFi Capture + Crack
2. Configure: Enter SSID, select "common-wifi" wordlist
3. Start and watch progress IN APP
4. If fails, try "top10k" or "rockyou" wordlist

### Testing Access Control (RFID/NFC):
1. Connect Flipper via Bluetooth or USB
2. Use NFC/RFID reader
3. Real card data shows immediately
4. Save for later emulation

### Network Reconnaissance:
1. Packet Sniffer (capture traffic)
2. Network Scanner (find devices)
3. Analyze packets for credentials

---

## Status: What Works

✅ WiFi Capture + Crack (real-time output)
✅ Hash Cracking (hashcat + john)
✅ WiFi Deauth attacks
✅ RFID brute force (Flipper)
✅ SubGHz brute force (Flipper)
✅ NFC/RFID reading (shows real data)
✅ Packet sniffing
✅ Process management (STOP works!)
✅ ESP32 Marauder commands

🔧 UI improvements ongoing
🔧 More in-app output displays coming

---

## Debug Scripts

If hardware not detected:
```bash
cd ~/Pen15
./flipper_usb_debug.sh   # Test Flipper
./awok_debug.sh          # Test AWOK
./usb_detect.sh          # Diagnose USB issues
```

---

**Need help? Check logs in each activity - they show what's happening!**
