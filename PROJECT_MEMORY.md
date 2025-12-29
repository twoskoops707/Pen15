# Project Memory - Pen15 Master Pentest Dashboard

## CRITICAL RULES
⚠️ **ALWAYS TEST CODE BEFORE COMMITTING TO BUILD**
⚠️ **NEVER STATE PROJECT COMPLETED WITHOUT TESTING**
⚠️ **BUILD VIA GITHUB ACTIONS ONLY - NOT LOCALLY**

## Current Status
✅ **Master Pentesting Suite - FULLY AUTOMATED**
✅ **Termux RUN_COMMAND execution (automatic command running)**
✅ **Flipper Zero integration via USB-C serial**
✅ **AWOK Mini V3 ESP32 Marauder automation**
🚀 **v1.0.12 - Complete rewrite with full hardware integration**

## What Makes This Special
🔥 **ZERO LEARNING CURVE** - One-click automated workflows
🔥 **FULL HARDWARE CONTROL** - Scripts control Flipper Zero + AWOK Mini V3 via USB
🔥 **ONLINE WORDLISTS** - Downloads rockyou.txt on-demand (no storage waste)
🔥 **STEP-BY-STEP GUIDES** - Every feature has detailed instructions
🔥 **NO MANUAL WORK** - App executes everything in Termux automatically

## Core Features

### 📡 WiFi Packet Capture & Cracking
**Fully Automated Workflow:**
1. **AUTO Capture + Crack** - One button does everything:
   - Enables monitor mode (airmon-ng)
   - Scans for target SSID
   - Captures WPA handshake (with deauth attack)
   - Downloads rockyou.txt wordlist (133MB)
   - Cracks password with aircrack-ng
   - Restores WiFi to normal mode
2. **Manual Steps** (if preferred):
   - Step 1: Capture handshake only
   - Step 2: Crack with wordlist

**Online Wordlist:** https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt

### 🐬 Flipper Zero Integration (USB-C Serial)
**Automated Python Scripts Control Flipper via /dev/ttyACM0:**

1. **Garage Door Brute Force**
   - Scans common frequencies: 300, 310, 315, 318, 390, 433.92 MHz
   - Captures and saves garage door signals
   - Replay saved signals from Flipper menu

2. **RFID Read & Clone**
   - Serial CLI access to Flipper
   - Commands: `rfid read`, `rfid save`, `rfid emulate`

3. **Sub-GHz Frequency Scanner**
   - Runs frequency analyzer for 30 seconds
   - Detects remote controls, car keys, wireless doorbells

4. **BadUSB Payloads**
   - Pre-made payloads (calculator, rickroll, reverse shell)
   - Instructions for SD card setup

**Manual Alternative:** All features accessible directly on Flipper Zero screen

### 📶 AWOK Mini V3 ESP32 Marauder (USB-C Serial)
**Automated Scripts Control via /dev/ttyUSB0:**

1. **WiFi Wardriving with GPS**
   - Scans WiFi networks with GPS coordinates logged
   - Saves results: SSID, BSSID, Channel, Signal strength, GPS location
   - Via Flipper screen OR Python automation

2. **Deauthentication Attack**
   - Disconnects clients from target WiFi
   - Forces WPA handshake for capture
   - Marauder command: `attack -t deauth -s SSID`

3. **Evil Portal (Captive Portal)**
   - Creates fake WiFi hotspot
   - Captures credentials via fake login page
   - Web interface: 192.168.4.1

4. **BLE Device Scanner**
   - Scans Bluetooth Low Energy devices
   - Detects smartwatches, fitness trackers, car keys
   - Saves results to file

## Devices Supported
- **Phone:** Samsung Galaxy Note 10+ (Android 11+, non-rooted)
- **Flipper Zero:** Connected via USB-C (serial /dev/ttyACM0)
- **AWOK Mini V3:** Dual ESP32 board with GPS, connects via GPIO to Flipper or USB

## Termux Execution Method
**PRIMARY:** Termux RUN_COMMAND (automatic execution)
- Permission: `com.termux.permission.RUN_COMMAND`
- Commands run automatically via Intent to `com.termux.app.RunCommandService`

**FALLBACK:** Clipboard method (if permission denied)
- Copies command → Opens Termux → User pastes manually
- Shows instructions to enable RUN_COMMAND

## Tools & Dependencies
**Via pkg install:**
- nmap, git, python, wget, curl, build-essential, clang, make, screen
- termux-api (for advanced features)

**Build from source (30-60 min):**
- Aircrack-ng suite (monitor mode, packet capture, WPA cracking)
- Hashcat (GPU password cracking)

**Python packages:**
- pyserial (for Flipper Zero & AWOK serial communication)
- pyflipper (Flipper Zero Python wrapper)

## Repository & Build
- **Repo:** https://github.com/twoskoops707/Pen15
- **Build:** GitHub Actions (never local)
- **APK Output:** app-debug.apk (~15MB)

## Latest Release
- **Version:** v1.0.12 (MASTER PENTEST SUITE)
- **Status:** 🚀 PENDING BUILD
- **Changes:**
  - ✅ Termux RUN_COMMAND integration (auto-execution)
  - ✅ WiFi capture + crack full automation
  - ✅ Flipper Zero USB-C serial control
  - ✅ AWOK Mini V3 ESP32 Marauder scripts
  - ✅ Online wordlist downloader (rockyou.txt)
  - ✅ Step-by-step guides for every feature
  - ✅ Python automation scripts

## UI Layout
**6 Main Card Sections:**
1. **Device Info** - Model, Android version, WiFi, IP, MAC
2. **Setup Commands** - Update packages, install tools
3. **Network Scanning** - Nmap, ARP scan
4. **📡 WiFi Hacking** - Capture + crack automation
5. **🐬 Flipper Zero** - Garage doors, RFID, Sub-GHz, BadUSB
6. **📶 AWOK Mini V3** - Wardriving, deauth, evil portal, BLE
7. **🔨 Install Tools** - Aircrack-ng, Hashcat
8. **⚙️ Utilities** - Open Termux, clear output

## First-Time Setup
1. Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/
2. Open Pentest Dashboard app
3. Tap "Update Packages" (grants RUN_COMMAND permission)
4. Tap "Install Pentest Tools"
5. Optional: Tap "Install Aircrack-ng Suite" (30 min)
6. Optional: Tap "Install Hashcat" (20 min)
7. Connect Flipper Zero via USB-C (serial access)
8. Attach AWOK Mini V3 to Flipper GPIO OR connect via USB

## Usage Examples

### Example 1: Crack Your WiFi Password
1. Enter WiFi SSID in text field
2. Tap "AUTO: Capture + Crack WiFi"
3. Wait 5-15 minutes
4. Password appears in output (if in rockyou.txt)

### Example 2: Clone Garage Door Remote
1. Connect Flipper Zero via USB-C
2. Tap "Garage Door Brute Force"
3. Python script runs, scans frequencies
4. Press garage remote button when prompted
5. Signal saved to Flipper - replay anytime

### Example 3: WiFi Wardriving
1. Attach AWOK Mini V3 to Flipper Zero
2. Enable GPS (DIP switch on back)
3. Tap "WiFi Wardriving (GPS Logged)"
4. Drive around for data collection
5. Results saved: /sdcard/pentest/wardrive_results.txt

## Security & Legal
⚠️ **AUTHORIZED TESTING ONLY**
- Only use on networks/devices you OWN
- Garage doors/frequencies you are authorized to test
- WiFi networks with written permission
- All pentest work for YOUR company/pentesting business

## Future Enhancements
- [ ] Real-time progress updates from Termux
- [ ] Result parsing and visualization
- [ ] Custom wordlist manager
- [ ] Hashcat GPU acceleration setup
- [ ] Export reports (PDF/CSV)
- [ ] Integration with Wigle WiFi wardriving database
