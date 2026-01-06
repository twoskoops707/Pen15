# Build Instructions

## Quick Build (Recommended)

### On Your Computer with Android Studio:
1. Clone this repo: `git clone <your-repo-url>`
2. Checkout the branch: `git checkout claude/fix-app-HAuOe`
3. Open in Android Studio
4. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK will be in: `app/build/outputs/apk/debug/app-debug.apk`

### On Your Computer with Command Line:
```bash
git clone <your-repo-url>
cd Pen15
git checkout claude/fix-app-HAuOe
./gradlew assembleDebug
```
APK location: `app/build/outputs/apk/debug/app-debug.apk`

### On Termux (On Your Phone):
```bash
# Install dependencies
pkg update && pkg upgrade
pkg install aapt openjdk-17 kotlin gradle

# Clone and build
git clone <your-repo-url>
cd Pen15
git checkout claude/fix-app-HAuOe
gradle assembleDebug
```

## Install on Phone

### Option 1: Direct Install
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option 2: Manual Install
1. Copy APK to your phone
2. Open APK file
3. Allow "Install from Unknown Sources" if prompted
4. Install

## What Was Fixed in This Build

### Root Detection System
- Added smart root detection
- Distinguishes between Flipper features (no root needed) and Termux features
- Shows warnings instead of blocking features

### Flipper Zero Features (Work without root)
- WiFi Deauth Attacks
- SubGHz Signal Capture/Replay
- RFID/NFC Read/Write/Emulate
- Infrared Control
- GPIO/UART Control
- BadUSB Payloads
- iButton Emulation
- Bluetooth Attacks

### Termux Features (Better with root, but still work)
- Network Scanning ✓
- Packet Capture ⚠️ (warns if no root)
- ARP Spoofing ⚠️ (warns if no root)
- Hash Cracking (CPU) ✓
- Exploit Database ✓
- OSINT Tools ✓

## Troubleshooting

### Build Fails with "SDK not found"
- Set `ANDROID_HOME` environment variable
- Point to your Android SDK location

### Build Fails with Network Error
- Check internet connection
- Check if behind firewall/proxy

### APK Won't Install
- Enable "Install from Unknown Sources" in Settings
- Uninstall old version first if upgrading
