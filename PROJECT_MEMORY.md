# Project Memory - Pen15 Master Pentest Dashboard

## CRITICAL RULES
⚠️ **ALWAYS TEST CODE BEFORE COMMITTING TO BUILD**
⚠️ **NEVER STATE PROJECT COMPLETED WITHOUT TESTING**
⚠️ **BUILD VIA GITHUB ACTIONS ONLY - NOT LOCALLY**
⚠️ **MAKE ALL CHANGES BEFORE TRIGGERING BUILD - DON'T BUILD FOR EVERY CHANGE**

## STABLE FILES - DO NOT REVIEW BEFORE BUILDS
✅ **activity_cheatsheet.xml** - Layout updated and stable
✅ **values/colors.xml** - Neon terminal hacker color scheme (complete)
✅ **GitHub Workflows** - Build workflows updated and stable
**NOTE:** These files are finalized. Do not suggest reviewing them before builds.

## Current Status (2026-01-18)
⏳ **Build #73+ IN PROGRESS** - USB Connection Stability Fixes

### 2026-01-18: USB Stability Overhaul
**PROBLEM:** Flipper Zero connection drops after ~5 seconds
**ROOT CAUSE IDENTIFIED:**
1. DTR/RTS signals causing Flipper to reset/timeout
2. No keep-alive mechanism to maintain connection
3. Missing color resources causing build failures

**FIXES APPLIED:**
- ✅ Removed DTR/RTS from SET_CONTROL_LINE_STATE (was 0x03, now 0x00)
- ✅ Added keep-alive mechanism (reads every 2 seconds)
- ✅ Switched from UsbRequest API to simple bulkTransfer
- ✅ Changed command termination from \r\n to just \r
- ✅ Added write retry mechanism
- ✅ Added connection health monitoring with auto-disconnect
- ✅ Fixed all missing color resources (glass_blur_*, glass_border_subtle, glass_success)
- ✅ Fixed bg_cyber_main.xml invalid '85%' dimension

**FILES MODIFIED:**
- `MainActivity.kt` - Complete USB stability rewrite (v73)
- `colors.xml` - Added missing glassmorphism colors
- `bg_cyber_main.xml` - Fixed invalid dimension syntax
- `activity_main_simple.xml` - Updated version to v73

---

## Previous Status (2026-01-16)
✅ **Build #64 - ALL BUTTONS WORKING**
✅ **Master Pentesting Suite - FULLY AUTOMATED**
✅ **Termux RUN_COMMAND execution (automatic command running)**
✅ **Modern Glassmorphism UI** - Complete redesign with tactical HUD
✅ **Flipper Zero USB/Bluetooth integration** - Working CLI commands
✅ **AWOK Mini V3 ESP32 Marauder automation**
✅ **Flipper CLI Commands FIXED** - Corrected invalid commands

### 2026-01-16 FIX: MainActivity buttons restored
All buttons were commented out during debugging. Fixed:
- RFID, NFC, SubGHz, Infrared, iButton, GPIO, BadUSB all work
- ESP32 Manager works
- Settings works
   - RFID: `rfid read` ✓ (works)
   - NFC: GUI-only (CLI removed in firmware)
   - iButton: `ikey read` ✓ (was using wrong command)
   - SubGHz: `subghz rx <freq>` ✓ (works)
   - IR: `ir rx` ✓ (works)
   - GPIO: `power 5v` ✓ (works)
   - BadUSB: `storage list /ext/badusb` ✓ (works)

## CRITICAL DISCOVERIES

### 2026-01-11: Flipper CLI Command Corrections
**Research confirmed actual working commands:**
- ✅ `rfid read` - Works (confirmed by official docs)
- ❌ `nfc read` - REMOVED in firmware (GitHub issue #3276)
  - NFC now requires GUI interaction only
  - CLI commands were removed after NFC refactor
- ❌ `ibutton read` - WRONG command name
  - ✅ Correct command: `ikey read`
- ✅ `subghz rx <frequency> <device>` - Works
- ✅ `ir rx` - Works (infrared)
- ✅ `storage list /ext/<path>` - Works

**Sources:**
- https://docs.flipper.net/zero/development/cli
- https://github.com/flipperdevices/flipperzero-firmware/issues/3276

### 2026-01-01: Flipper Architecture
**Flipper Companion App can't work with USB!**
- Custom apps can't listen on USB CDC (interface is in use by the app itself)
- `FuriHalSerialIdUsb` doesn't exist in Flipper SDK
- Solution: Use Flipper's built-in CLI via USB CDC instead
- NO custom .fap app needed!
- See: CRITICAL_ARCHITECTURE_FIX.md for details

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

### 📶 AWOK Mini V3 ESP32 Marauder (GPIO UART)
**Connected to Flipper Zero via GPIO pins**

**Correct Commands (confirmed 2026-01-11):**

1. **WiFi Scanning**
   - `scanap` - Scan for access points
   - `list -a` - List scanned APs
   - `stopscan` - Stop scanning

2. **Target Selection**
   - `select -a 0` - Select AP by index
   - `clearap` - Clear selection

3. **WiFi Attacks**
   - `attack -t deauth` - Deauth flood attack
   - `attack -t beacon` - Beacon spam
   - `attack -t probe` - Probe request spam

4. **BLE Attacks**
   - `btspamall` - BLE spam all
   - `sniffbt` - Sniff Bluetooth

**Connection:** AWOK Mini V3 → Flipper GPIO (UART) → USB to Android
**Resources:** https://github.com/justcallmekoko/ESP32Marauder/wiki

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
- **Version:** Build #54 (2026-01-11)
- **Status:** ✅ SUCCESSFULLY BUILT
- **APK:** https://github.com/twoskoops707/Pen15/releases/tag/build-54
- **Changes:**
  - ✅ Modern glassmorphism UI with tactical HUD design
  - ✅ Flipper Zero USB/Bluetooth connection managers
  - ✅ FIXED: Corrected all Flipper CLI commands
  - ✅ BaseToolActivity architecture for all tools
  - ✅ Working RFID/SubGHz/IR/iButton/BadUSB/GPIO
  - ✅ NFC properly documented as GUI-only
  - ✅ Removed blocking operations from connection init
  - ✅ 20+ pentesting tools fully implemented

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
