# TESTING CHECKLIST - v1.0.87+

## DOWNLOADS (LATEST)

**Android App v1.0.87:**
```
https://github.com/twoskoops707/Pen15/releases/download/debug-v1.0.87/app-debug.apk
```

**Flipper Companion v1.0.17:**
```
https://github.com/twoskoops707/Pen15/releases/download/flipper-v1.0.17/pentest_companion.fap
```

## PRE-TESTING SETUP

### 1. Install Flipper Companion App
```bash
# Download .fap file
# Copy to Flipper SD card at: /apps/Tools/pentest_companion.fap
# Reboot Flipper
# Launch: Apps → Tools → Pentest Companion
```

### 2. Install Android App
```bash
adb install app-debug.apk
# Or download directly on phone
```

### 3. Install Termux (Required for brute force & pentesting)
```bash
# Install from F-Droid (NOT Play Store)
# Grant storage permission
pkg update && pkg upgrade
pkg install root-repo
pkg install termux-api
```

## CONNECTION TESTING

### ✅ Test 1: USB Connection (PRIMARY)
1. Connect Flipper via USB-C cable
2. Open Android app
3. Click "CONNECT" button
4. **EXPECTED:** Status shows "CONNECTED • USB-C"
5. **FAIL IF:** Shows "CONNECTED" without USB plugged in
6. **FAIL IF:** Shows "NO FLIPPER FOUND" when Flipper IS connected

### ✅ Test 2: Bluetooth Detection (SECONDARY)
1. Pair Flipper in Android Bluetooth settings
2. Disconnect USB
3. Click "CONNECT" button
4. **EXPECTED:** Status shows "FOUND: Flipper Zero" (NOT "CONNECTED")
5. **EXPECTED:** Toast says "use USB connection instead"
6. **FAIL IF:** Shows "CONNECTED" via Bluetooth (Bluetooth connection not implemented yet)

### ✅ Test 3: Serial Communication
1. Connect via USB
2. Go to SubGHz screen
3. Click "START SCANNING"
4. **EXPECTED:** Logs show "Testing connection with: device_info"
5. **EXPECTED:** Command sent successfully
6. **FAIL IF:** No response or timeout

## BRUTE FORCE TESTING

### ✅ Test 4: SubGHz Brute Force
1. Connect Flipper via USB
2. Go to SubGHz screen
3. Set frequency (e.g., 433.920 MHz)
4. Click "BRUTE FORCE" button
5. Configure:
   - Start Code: 000000
   - End Code: 000100 (small range for testing!)
   - Delay: 100ms
   - Protocol: Princeton
6. Click START
7. **EXPECTED:**
   - Termux opens automatically
   - Bash script executes
   - Progress shows every 100 codes
   - Commands send to /dev/ttyUSB0 or /dev/ttyACM0
8. **FAIL IF:**
   - Shows "BRUTE FORCE COMPLETE" instantly (fake)
   - Termux doesn't open
   - No progress tracking

### ✅ Test 5: RFID Brute Force
1. Connect Flipper via USB
2. Go to RFID screen
3. Click "BRUTE FOB" button
4. Configure:
   - Start ID: 0000000000
   - End ID: 0000000100 (small range!)
   - Delay: 100ms
   - Frequency: 125000
5. Click START
6. **EXPECTED:**
   - Termux opens
   - Script executes with progress
   - Commands send to Flipper
7. **FAIL IF:** Instant completion

### ✅ Test 6: Input Validation
**Test Invalid Ranges:**
1. Start Code > End Code → Should show ERROR
2. Range > 100 million codes → Should reject
3. Delay < 10ms or > 60000ms → Should reject

## TERMUX INTEGRATION TESTING

### ✅ Test 7: WiFi Attack
1. Go to WiFi Deauth screen
2. Click "START ATTACK"
3. Enter WiFi details
4. **EXPECTED:**
   - Termux opens
   - airmon-ng enables monitor mode
   - airodump-ng captures handshake
   - aireplay-ng sends deauth
5. **FAIL IF:** Shows fake results or doesn't open Termux

### ✅ Test 8: Hash Cracking
1. Go to Hash Cracker screen
2. Enter hash (e.g., MD5)
3. Select wordlist URL
4. **EXPECTED:**
   - Termux opens
   - Hashcat executes with online wordlist
   - Progress shows in Termux
5. **FAIL IF:** Instant result

### ✅ Test 9: Packet Sniffing
1. Go to Packet Sniffer screen
2. Start capture
3. **EXPECTED:**
   - tcpdump captures to file
   - tshark analyzes packets
   - Credentials extracted if found
4. **FAIL IF:** Fake captures

## SCRIPT BUILDER TESTING

### ✅ Test 10: Payload Generation
1. Go to BadUSB screen
2. Click "Build Script"
3. Select payload type (Keylogger/Reverse Shell/etc.)
4. Fill in parameters
5. Click GENERATE
6. **EXPECTED:**
   - Valid Ducky Script generated
   - Copy to clipboard works
   - Script includes configured parameters
7. **FAIL IF:** Generic placeholder script

## UI/UX TESTING

### ✅ Test 11: Modern Icon
1. Check app icon in launcher
2. **EXPECTED:**
   - Purple/pink cyberpunk gradient
   - Terminal window with command prompt
   - Lock symbol accent
   - Circuit board elements
3. **FAIL IF:** Old generic icon

### ✅ Test 12: Connection Status
1. Launch app WITHOUT Flipper connected
2. **EXPECTED:** Status shows "DISCONNECTED"
3. Connect Flipper via USB
4. Click CONNECT
5. **EXPECTED:** Status changes to "CONNECTED • USB-C"
6. **FAIL IF:** Shows connected without device

### ✅ Test 13: Parameter Dialogs
1. Try any attack that uses parameters
2. **EXPECTED:**
   - Dialog shows with all required fields
   - Default values populated
   - Required fields marked with *
   - Validation works
3. **FAIL IF:** No dialog or fields missing

## RESOURCE MANAGEMENT TESTING

### ✅ Test 14: Thread Cleanup
1. Connect via USB
2. Navigate to any screen
3. Go back to main screen
4. Disconnect
5. Check Android battery usage
6. **EXPECTED:** No background threads consuming battery
7. **FAIL IF:** High battery drain after disconnect

### ✅ Test 15: Memory Leaks
1. Connect/disconnect 10 times rapidly
2. Check app memory usage in Android settings
3. **EXPECTED:** Memory usage stays stable
4. **FAIL IF:** Memory continuously increases

## CRITICAL BUG CHECKS

### ✅ Test 16: No Fake Results
**Check ALL activities for fake results:**
- ❌ RFIDActivity - NO fake "Working Code Found"
- ❌ BluetoothActivity - NO fake device discovery
- ❌ GPIOActivity - NO fake WiFi scans
- ❌ IButtonActivity - NO fake key reads
- ✅ All should say "Requires Flipper" or execute real commands

### ✅ Test 17: No Instant Completions
**Time-consuming operations should NEVER complete instantly:**
- Brute force attacks should take minutes/hours
- Hash cracking depends on wordlist size
- Packet capture runs until stopped
- ❌ FAIL if anything shows "COMPLETE" in < 1 second

## PERMISSIONS TESTING

### ✅ Test 18: USB Permission
1. First launch with Flipper connected
2. **EXPECTED:** Android asks for USB permission
3. Grant permission
4. **EXPECTED:** Connection succeeds
5. **FAIL IF:** No permission dialog

### ✅ Test 19: Bluetooth Permission
1. Try Bluetooth scan
2. **EXPECTED:** Permission request if needed
3. **FAIL IF:** Crashes on permission denied

## STRESS TESTING

### ✅ Test 20: Rapid Button Clicks
1. Click CONNECT button 10 times rapidly
2. **EXPECTED:** No crashes, handles gracefully
3. **FAIL IF:** App freezes or crashes

### ✅ Test 21: Connection During Operation
1. Start brute force attack
2. Disconnect Flipper mid-attack
3. **EXPECTED:** Graceful error handling
4. **FAIL IF:** App crashes

## REPORTING BUGS

When you find issues, provide:
1. Exact steps to reproduce
2. Expected vs actual behavior
3. Android version
4. Flipper firmware version
5. Screenshots if applicable
6. Logcat output if crash

## POST-TESTING

After completing checklist:
1. List ALL issues found
2. Rate severity (Critical/Important/Minor)
3. Provide reproduction steps
4. I'll fix immediately

## KNOWN LIMITATIONS

✅ **Working:**
- USB connection detection
- Serial communication
- SubGHz brute force (real execution)
- RFID brute force (real execution)
- Termux integration (all tools)
- Script builder (4 payload types)
- Parameter dialogs with validation
- Modern UI with new icon

⏳ **Not Yet Implemented:**
- Bluetooth connection (only detection works)
- Signal decoding callbacks (SubGHz RX data parsing)
- ESP32 Marauder integration
- Saved signal library
- OSINT unified scanner UI
- API key manager UI

❌ **Won't Work:**
- Features requiring Flipper companion app if .fap not installed
- Termux tools if Termux not installed
- Root-only features on unrooted phone
