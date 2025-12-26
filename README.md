# Pentest Dashboard - Android Termux GUI

A Kotlin-based Android app that provides a graphical user interface for managing Termux pentesting commands. This ethical penetration testing dashboard allows you to execute common pentest tools on your own devices and networks through an intuitive Material Design interface.

## Legal Disclaimer

**CRITICAL WARNING:** This app is designed EXCLUSIVELY for:
- Testing devices and networks YOU OWN
- Authorized penetration testing with WRITTEN PERMISSION
- Educational and research purposes in controlled environments
- Security testing your own systems

**NEVER use this app for:**
- Unauthorized access to any device, network, or system
- Testing without explicit written permission
- Malicious attacks or exploitation
- Any violation of laws

By using this app, you acknowledge you are solely responsible for your actions. Unauthorized access is a criminal offense.

## Features

### Command Categories

**📦 Setup Commands**
- Update Termux packages
- Install pentest tools (nmap, hashcat, aircrack-ng, bluez, etc.)

**🌐 Network Scanning**
- Nmap quick scan (-sn)
- Nmap detailed scan (-A)
- ARP scan for local network device discovery

**🔧 Other Tools**
- Bluetooth device scanning
- Hashcat version check
- Direct Termux app launch

**⚙️ Utilities**
- Command output display with terminal styling
- Clear output functionality
- Customizable target IP/network input

## Technical Specifications

- **Language:** Kotlin
- **Minimum SDK:** Android 11 (API 30)
- **Target SDK:** Android 14 (API 34)
- **UI Framework:** Material Components
- **Architecture:** Single Activity with Coroutines
- **Permissions:** INTERNET, ACCESS_WIFI_STATE, BLUETOOTH_CONNECT

## Requirements

### On Your Phone
1. **Android 11 or later** (Samsung Galaxy Note 10+ with Android 12 recommended)
2. **Termux** installed from [F-Droid](https://f-droid.org/en/packages/com.termux/)
   - **IMPORTANT:** Do NOT install from Google Play Store (outdated)
   - F-Droid version: https://f-droid.org/en/packages/com.termux/
3. **Android Studio** (for building) or use GitHub Actions for automated builds

### Development Environment
- **Android Studio Hedgehog (2023.1.1) or later**
- **JDK 17 or later**
- **Gradle 8.2+** (included in project)
- **Kotlin 1.9.20+** (included in project)

## Project Structure

```
Pen15/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/pentest/dashboard/
│   │       │   └── MainActivity.kt          # Main app logic
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml    # UI layout
│   │       │   └── values/
│   │       │       ├── colors.xml           # Color theme
│   │       │       ├── strings.xml          # String resources
│   │       │       └── themes.xml           # Material theme
│   │       └── AndroidManifest.xml          # App manifest with permissions
│   ├── build.gradle                         # App-level build config
│   └── proguard-rules.pro                   # ProGuard rules
├── build.gradle                             # Project-level build config
├── settings.gradle                          # Project settings
├── gradle.properties                        # Gradle properties
└── README.md                                # This file
```

## Building the APK

### Method 1: Android Studio (Local Build)

#### Step 1: Clone the Repository
```bash
git clone https://github.com/twoskoops707/Pen15.git
cd Pen15
```

#### Step 2: Open in Android Studio
1. Launch Android Studio
2. Click **File → Open**
3. Navigate to the `Pen15` directory
4. Click **OK**
5. Wait for Gradle sync to complete (first time takes 5-10 minutes)

#### Step 3: Build APK
1. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for build to complete
3. Click **locate** in the notification popup
4. APK location: `app/build/outputs/apk/debug/app-debug.apk`

#### Step 4: Install on Phone
```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or copy to phone and install manually
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
# Then open from file manager and install
```

### Method 2: GitHub Actions (Automated Cloud Build)

#### Step 1: Enable GitHub Actions
1. Go to your repository: https://github.com/twoskoops707/Pen15
2. Create `.github/workflows/build.yml`:

```yaml
name: Android Build

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build with Gradle
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v3
        with:
          name: pentest-dashboard-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

#### Step 2: Trigger Build
- Push to main branch, or
- Go to **Actions** tab → **Android Build** → **Run workflow**

#### Step 3: Download APK
1. Go to **Actions** tab
2. Click on latest successful workflow
3. Download artifact: **pentest-dashboard-apk**
4. Extract ZIP and install APK on phone

### Method 3: Gradle Command Line (Terminal/Termux)

**Note:** Building on Termux requires significant RAM and may not work on all devices.

```bash
# On PC/Mac/Linux
./gradlew assembleDebug

# Output APK location
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

## Installation & Setup

### Step 1: Install Termux
```bash
# Download from F-Droid (REQUIRED)
# https://f-droid.org/en/packages/com.termux/

# After installing, open Termux and run:
pkg update && pkg upgrade -y
```

### Step 2: Install the Dashboard APK
```bash
# Enable "Install from Unknown Sources" in Android settings
# Install the APK (app-debug.apk)
```

### Step 3: Install Termux Packages (via Dashboard)
1. Open **Pentest Dashboard** app
2. Tap **"Install Pentest Tools"**
3. This installs: nmap, hashcat, aircrack-ng, bluez, net-tools, arp-scan, termux-api, python, git, wget, curl

### Step 4: Grant Permissions
- The app will request INTERNET, WIFI_STATE, and BLUETOOTH_CONNECT permissions
- Grant all for full functionality

## Usage Guide

### Basic Network Scan
1. Enter target network in the input field (e.g., `192.168.1.0/24`)
2. Tap **"Nmap Quick Scan (-sn)"**
3. Command is sent to Termux
4. Check Termux app for output (app will auto-open it)

### Detailed Device Scan
1. Enter target IP (e.g., `192.168.1.1`)
2. Tap **"Nmap Detailed Scan (-A)"**
3. This performs OS detection, version detection, script scanning, and traceroute
4. Takes 5-15 minutes depending on target

### Install Tools Workflow
1. First-time users: Tap **"Update Packages"** first
2. Then tap **"Install Pentest Tools"**
3. Wait for installation (3-10 minutes)
4. Verify with **"Check Hashcat Version"**

### Bluetooth Scanning
1. Enable Bluetooth on your phone
2. Tap **"Bluetooth Scan"**
3. View nearby Bluetooth devices in Termux output

## How It Works

### Command Execution Methods

The app uses multiple methods to execute Termux commands:

**Method 1: Termux API Intent (Primary)**
```kotlin
val intent = Intent()
intent.setClassName("com.termux", "com.termux.app.RunCommandService")
intent.action = "com.termux.app.RunCommandService.RUN_COMMAND"
intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
startService(intent)
```

**Method 2: Fallback - Open Termux**
- If API method fails, the app opens Termux and displays the command
- User manually runs the command in Termux

### Why Not Runtime.exec()?

`Runtime.getRuntime().exec()` does NOT work for Termux commands because:
- Termux runs in a separate sandboxed environment
- Android security prevents direct inter-process execution
- Must use Termux API intents or manual command entry

## Troubleshooting

### Issue: "Termux not installed"
**Solution:**
- Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/
- DO NOT use Google Play Store version (broken/outdated)

### Issue: Commands not executing
**Solution:**
- Ensure Termux is installed and opened at least once
- Grant all app permissions
- Try Method 2: Manually run commands in Termux

### Issue: "Permission denied" errors
**Solution:**
```bash
# In Termux, grant storage permission:
termux-setup-storage

# Grant Termux API permission:
pkg install termux-api -y
```

### Issue: Nmap/Hashcat not found
**Solution:**
```bash
# In Termux, manually install:
pkg install nmap hashcat -y
```

### Issue: Build fails in Android Studio
**Solution:**
- Update Android Studio to latest version
- Sync Gradle: **File → Sync Project with Gradle Files**
- Clean build: **Build → Clean Project**, then rebuild
- Check JDK version: **File → Project Structure → SDK Location** (should be JDK 17+)

### Issue: APK won't install on phone
**Solution:**
- Enable "Install Unknown Apps" for your file manager
- Settings → Apps → [Your File Manager] → Install Unknown Apps → Allow
- Minimum Android version is 11 (API 30)

## Customization

### Adding New Commands

Edit `MainActivity.kt` and add button + logic:

```kotlin
// In onCreate():
findViewById<MaterialButton>(R.id.btnMyCommand).setOnClickListener {
    executeTermuxCommand("your-command-here")
}
```

Then add button to `activity_main.xml`:

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/btnMyCommand"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="My Custom Command"
    app:icon="@android:drawable/ic_menu_send"
    app:iconGravity="start"/>
```

### Changing Theme Colors

Edit `app/src/main/res/values/colors.xml`:

```xml
<color name="primary">#YourColor</color>
<color name="accent">#YourColor</color>
```

## Security Considerations

- **Local Execution Only:** All commands run locally in Termux, not on external systems
- **No Network Attacks:** App does not perform attacks, only reconnaissance on owned networks
- **Permissions:** Only requests necessary permissions (no camera, location, etc.)
- **Open Source:** All code is visible and auditable
- **Ethical Use:** Designed for authorized testing only

## Roadmap

Potential future enhancements:
- Command history and favorites
- Saved scan profiles
- Export results to file
- Integration with Flipper Zero
- WiFi Pineapple control
- Custom command input field
- Background task execution
- Push notifications for scan completion

## Contributing

This is a personal project, but suggestions and improvements are welcome:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Support

For issues or questions:
- Check **Troubleshooting** section above
- Review Termux documentation: https://wiki.termux.com/
- Check Android permissions settings

## License

This project is provided "as-is" for educational and authorized security testing purposes only.

**MIT License** - Use at your own risk. No warranty provided.

---

## Quick Reference

### Common Termux Commands
```bash
# Update packages
pkg update && pkg upgrade -y

# Install tools
pkg install nmap hashcat aircrack-ng bluez net-tools arp-scan -y

# Network scan
nmap -sn 192.168.1.0/24

# Detailed scan
nmap -A 192.168.1.1

# ARP scan
arp-scan --localnet

# Bluetooth scan
hcitool scan
```

### Build Commands
```bash
# Local build
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Clean build
./gradlew clean assembleDebug
```

---

**Remember:** Always test ethically. Always get permission. Always stay legal. 🛡️
