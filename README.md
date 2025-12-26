# Pentest Dashboard - Android APK for Termux + Flipper Zero + AWOK Dual Mini v3

**Download the APK from GitHub Actions and start pentesting on your Android phone!**

A Kotlin Android app that provides a **graphical user interface** for Termux pentesting commands, designed to work with:
- **Samsung Galaxy Note 10+** (or any Android 11+ device)
- **Flipper Zero** (portable multi-tool hacking device)
- **AWOK Dual Mini v3** (ESP32-based WiFi wardriving device with Marauder firmware)

## 🚨 Legal Disclaimer

**CRITICAL WARNING:** This app is for **AUTHORIZED TESTING ONLY**

✅ **Legal uses:**
- Testing YOUR OWN devices and networks
- Authorized penetration testing with WRITTEN PERMISSION
- Educational purposes in controlled environments
- Security research on systems you own

❌ **ILLEGAL uses (DON'T DO THIS):**
- Unauthorized access to any network, device, or system
- Testing without explicit written permission
- Any malicious attacks or exploitation

**You are solely responsible for your actions. Unauthorized hacking is a criminal offense.**

---

## 📥 How to Get the APK

### Download Pre-Built APK (Easiest)

1. **Go to the GitHub Actions page:**
   - https://github.com/twoskoops707/Pen15/actions

2. **Click on the latest successful workflow** (green checkmark ✅)

3. **Scroll down to "Artifacts"**

4. **Download:** `pentest-dashboard-debug-apk`

5. **Extract the ZIP file** → You'll get `app-debug.apk`

6. **Transfer to your phone** and install

---

## 📱 Installation Steps

### Step 1: Install Termux (REQUIRED)

**⚠️  IMPORTANT:** Install Termux from **F-Droid ONLY** (NOT Google Play Store)

1. Download F-Droid: https://f-droid.org/
2. Install F-Droid APK on your phone
3. Open F-Droid app
4. Search for "Termux"
5. Install **Termux** (latest version)

**Why F-Droid?** Google Play Store version is outdated and broken. F-Droid has the official maintained version.

### Step 2: Install the Pentest Dashboard APK

1. Download `app-debug.apk` from GitHub Actions
2. Copy to your phone (via USB/cloud/email)
3. Open the APK file
4. Android will ask **"Install Unknown App?"**
5. Tap **Settings** → Enable **"Allow from this source"**
6. Tap **Install**

### Step 3: Grant Permissions

When you first open the app, it will request:
- ✅ Internet access
- ✅ WiFi state access
- ✅ Bluetooth access

**Tap "Allow" for all permissions.**

---

## 🎯 Quick Start Guide

### First-Time Setup (5 minutes)

1. **Open Pentest Dashboard** app
2. **Tap "Update Packages"** (wait 2-3 minutes)
3. **Tap "Install Pentest Tools"** (wait 3-5 minutes)
4. **Done!** You now have: nmap, git, python, wget, curl

### Optional: Build Advanced Tools (30-60 minutes each)

These tools were **removed from Termux repositories** and must be built from source:

- **Tap "Install Aircrack-ng (GitHub)"** → Builds [aircrack-ng](https://github.com/aircrack-ng/aircrack-ng)
- **Tap "Install Hashcat (GitHub)"** → Builds [hashcat](https://github.com/hashcat/hashcat)

**Note:** Building takes 30-60 minutes per tool. Your phone may get warm. Make sure you have:
- ✅ Good battery charge (or plugged in)
- ✅ At least 2GB free storage
- ✅ Stable internet connection

---

## 🛠️ What's Included

### ✅ Tools Available via Termux PKG (Install in 5 min)

```bash
nmap         # Network scanner
git          # Version control
python       # Scripting language
wget         # Download files
curl         # Transfer data
termux-api   # Android API access
build-essential # Compilers for building tools
```

### 🔨 Tools Built from GitHub (30-60 min each)

| Tool | GitHub Repo | What It Does |
|------|-------------|--------------|
| **Aircrack-ng** | [aircrack-ng/aircrack-ng](https://github.com/aircrack-ng/aircrack-ng) | WiFi security auditing (WPA/WPA2 cracking) |
| **Hashcat** | [hashcat/hashcat](https://github.com/hashcat/hashcat) | Advanced password recovery |

**Why build from source?**
Termux removed hacking tools (hashcat, aircrack-ng, hydra, metasploit) from official repositories in 2023. You must compile them manually.

Sources:
- [Termux FAQ](https://wiki.termux.com/wiki/FAQ)
- [GitHub Issue: hashcat in termux](https://github.com/termux/termux-app/issues/663)
- [Aircrack-ng Termux Guide](https://guidetolinux.com/how-to-install-aircrack-ng-in-termux/)

---

## 📡 Device Integration

### Flipper Zero

**What is it?** Portable multi-tool for hackers (NFC, RFID, Sub-GHz, IR, iButton, BadUSB)

**How to use with this app:**
1. Connect Flipper Zero to your Note 10+ via USB-C
2. Use Termux to communicate with Flipper
3. Example: Flash firmware, transfer files, run scripts

**Resources:**
- Official site: https://flipperzero.one/
- Firmware updates: https://github.com/flipperdevices/flipperzero-firmware

### AWOK Dual Mini v3

**What is it?** ESP32-based WiFi wardriving device for Flipper Zero with touchscreen

**Features:**
- WiFi scanning and packet capture
- BLE (Bluetooth Low Energy) scanning
- ESP32 Marauder firmware support
- GPS logging for wardriving
- Touchscreen interface

**How to use with this app:**
1. Connect AWOK Dual Mini v3 to Flipper Zero
2. Flash ESP32 Marauder firmware
3. Use Termux to analyze captured data

**Resources:**
- Official product: https://awokdynamics.com/products/dual-mini-v3
- ESP32 Marauder: https://github.com/justcallmekoko/ESP32Marauder
- Lab401 product page: https://lab401.com/products/awok-dual-touch-v3

---

## 🎮 App Features & Usage

### Network Scanning

**Quick Network Scan**
1. Enter target network (e.g., `192.168.1.0/24`)
2. Tap **"Nmap Quick Scan (-sn)"**
3. Finds all devices on your network

**Detailed Device Scan**
1. Enter target IP (e.g., `192.168.1.1`)
2. Tap **"Nmap Detailed Scan (-A)"**
3. Shows OS, open ports, services

**ARP Scan**
1. Tap **"ARP Scan"**
2. Lists all devices with MAC addresses
3. Shows manufacturer info

### Installing Removed Tools

**Aircrack-ng (WiFi Hacking)**
```bash
# What the app does when you tap the button:
cd ~
git clone https://github.com/aircrack-ng/aircrack-ng
cd aircrack-ng
autoreconf -i
./configure --with-experimental
make
make install
```

**Hashcat (Password Cracking)**
```bash
# What the app does when you tap the button:
cd ~
git clone https://github.com/hashcat/hashcat
cd hashcat
make
make install
```

### Output Display

- All command output appears in the **black terminal section** at the bottom
- Green text = success
- Commands are sent to Termux automatically
- If auto-execution fails, app opens Termux for manual entry

---

## 📋 Step-by-Step Workflow Examples

### Example 1: Scan Your Home Network

```
1. Open Pentest Dashboard
2. Enter: 192.168.1.0/24 (or your network range)
3. Tap "Nmap Quick Scan"
4. Termux opens and runs: nmap -sn 192.168.1.0/24
5. View all devices on your network
```

### Example 2: Capture WiFi Handshake (with AWOK + Flipper)

```
1. Connect AWOK Dual Mini v3 to Flipper Zero
2. On Flipper: GPIO → ESP32 WiFi Marauder
3. Capture handshake on target network (YOUR network)
4. Save .pcap file to SD card
5. Transfer to phone
6. Use Pentest Dashboard → "Install Aircrack-ng"
7. In Termux: aircrack-ng -w wordlist.txt capture.pcap
```

### Example 3: Build Hashcat for Password Cracking

```
1. Open Pentest Dashboard
2. Tap "Install Hashcat (GitHub)"
3. Wait 30-60 minutes for compilation
4. In Termux: hashcat -m 22000 hash.hc22000 rockyou.txt
5. Crack captured WiFi password
```

---

## 🔧 Troubleshooting

### "Termux not installed"
- Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/
- DO NOT use Google Play Store version

### "Permission denied" errors
```bash
# In Termux:
termux-setup-storage
pkg install termux-api
```

### "Command not found: nmap"
```bash
# In Termux:
pkg update
pkg install nmap
```

### Build fails for aircrack-ng/hashcat
```bash
# Missing dependencies - install:
pkg install build-essential clang make autoconf automake libtool pkg-config
```

### App won't install
- Enable "Install Unknown Apps" for your file manager
- Settings → Apps → [File Manager] → Install Unknown Apps → Allow
- Minimum Android 11 required

---

## 📚 Learning Resources

### Termux Basics
- Official Wiki: https://wiki.termux.com/
- Command Guide: https://www.termuxcommands.com/
- GitHub: https://github.com/termux/termux-app

### Pentesting Tools
- Nmap Guide: https://nmap.org/book/man.html
- Aircrack-ng Docs: https://www.aircrack-ng.org/documentation.html
- Hashcat Wiki: https://hashcat.net/wiki/

### Flipper Zero
- Official Docs: https://docs.flipper.net/
- Awesome Flipper: https://github.com/djsime1/awesome-flipperzero

### AWOK Dual Mini v3
- Product Page: https://awokdynamics.com/products/dual-mini-v3
- ESP32 Marauder: https://github.com/justcallmekoko/ESP32Marauder

---

## 🏗️ Building from Source

### Build APK with GitHub Actions (Recommended)

1. Fork this repository
2. Go to Actions tab
3. Click "Android CI Build with Debugging"
4. Click "Run workflow"
5. Wait 10-15 minutes
6. Download APK from Artifacts

### Build APK Locally (Requires Android Studio)

```bash
git clone https://github.com/twoskoops707/Pen15.git
cd Pen15
# Open in Android Studio
# Build → Build Bundle(s) / APK(s) → Build APK(s)
```

---

## 📁 Project Structure

```
Pen15/
├── app/
│   ├── src/main/
│   │   ├── java/com/pentest/dashboard/
│   │   │   └── MainActivity.kt      # App logic
│   │   ├── res/layout/
│   │   │   └── activity_main.xml    # UI layout
│   │   └── AndroidManifest.xml      # Permissions
│   └── build.gradle                 # Dependencies
├── .github/workflows/
│   └── build.yml                    # Auto-build config
└── README.md                        # This file
```

---

## 🤝 Contributing

This is a personal pentesting toolkit, but suggestions welcome:
1. Fork the repo
2. Make improvements
3. Submit a pull request

---

## ⚖️ License

MIT License - Use at your own risk for **authorized testing only**.

---

## 🔗 Quick Links

- **Repository:** https://github.com/twoskoops707/Pen15
- **Download APK:** https://github.com/twoskoops707/Pen15/actions
- **Termux F-Droid:** https://f-droid.org/en/packages/com.termux/
- **Flipper Zero:** https://flipperzero.one/
- **AWOK Dynamics:** https://awokdynamics.com/
- **Aircrack-ng GitHub:** https://github.com/aircrack-ng/aircrack-ng
- **Hashcat GitHub:** https://github.com/hashcat/hashcat

---

**Remember:** Always get permission. Always test ethically. Always stay legal. 🛡️

**Sources:**
- [AWOK Dual Mini v3 Product Page](https://awokdynamics.com/products/dual-mini-v3)
- [AWOK Dual Touch v3 on Lab401](https://lab401.com/products/awok-dual-touch-v3)
- [ESP32 Marauder GitHub](https://github.com/justcallmekoko/ESP32Marauder)
- [Termux FAQ - Removed Packages](https://wiki.termux.com/wiki/FAQ)
- [How to Install Nmap in Termux 2025](https://www.termuxcommands.com/how-to-install-nmap-in-termux-2025/)
- [Aircrack-ng for Termux Guide](https://guidetolinux.com/how-to-install-aircrack-ng-in-termux/)
- [Hashcat Android/Termux Support](https://github.com/hashcat/hashcat/pull/4563)
