# Handoff

## State
- main branch at v1.0.129, last commits fixed AWOK direct USB dual-path, ESP32SerialManager singleton, status banner, scroll lock, Marauder AP parser
- awok-only branch is primary dev branch for ESP32/AWOK direct serial (no Termux dependency)
- Momentum firmware VID/PID spoofing handled via 3-tier fallback in FlipperUSBManager

## Next
1. Test Momentum firmware connection on device (`adb logcat | grep FlipperUSBManager`)
2. Verify ESP32 serial commands work end-to-end on device
3. Merge awok-only improvements to main when stable

## Context
- Device is NON-ROOTED Samsung Galaxy Note 10+, Android 11 — no tcpdump, no SYN scans
- ESP32 serial uses usb-serial-for-android (mik3y v3.9.0) directly
- Must add `allow-external-apps=true` to `~/.termux/termux.properties` for ProcessManager intents to work
