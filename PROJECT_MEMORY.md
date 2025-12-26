# Project Memory - Pen15 Pentest Dashboard

## Current Status
✅ **Android APK app - fully functional code**
❌ **GitHub storage quota full - can't upload artifacts**
✅ **APK builds successfully on GitHub Actions**

## What Works
- Kotlin Android app with Material Design UI
- Termux integration via intents
- Network scanning (nmap)
- Build from source buttons for aircrack-ng and hashcat
- REAL GitHub repos (no fake commands)

## Devices Supported
- Samsung Galaxy Note 10+ (Android 11+)
- Flipper Zero
- AWOK Dual Mini v3 (ESP32 Marauder)

## Tools Included
**Available via pkg:**
- nmap, git, python, wget, curl, build-essential

**Build from source (GitHub):**
- Aircrack-ng: https://github.com/aircrack-ng/aircrack-ng
- Hashcat: https://github.com/hashcat/hashcat

## Repository
- URL: https://github.com/twoskoops707/Pen15
- Commits: 7 total
- All code is complete and working

## Current Issue
GitHub Actions artifact storage quota exceeded. Need to either:
1. Wait 6-12 hours for quota reset
2. Build locally with: `./gradlew assembleRelease`
3. Use new release workflow (no artifacts)

## Next Steps
- Trigger release.yml workflow
- APK will build but not upload (storage quota)
- User can view build logs to confirm success
