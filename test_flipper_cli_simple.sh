#!/data/data/com.termux/files/usr/bin/bash

echo "=== SIMPLE FLIPPER CLI TEST ==="
echo ""
echo "BEFORE running this:"
echo "1. Make sure Flipper is at DESKTOP (see dolphin)"
echo "2. Settings → System → USB Channel = 0 (CLI)"
echo "3. Press ENTER when ready..."
read

# Find device
DEV=""
for d in /dev/ttyACM0 /dev/ttyUSB0; do
    if [ -e "$d" ]; then
        DEV="$d"
        break
    fi
done

if [ -z "$DEV" ]; then
    echo "ERROR: No USB device found!"
    exit 1
fi

echo "Found: $DEV"
echo ""
echo "Sending simple commands..."
echo ""

# Send help command
echo "help" > $DEV
sleep 1

# Read response
echo "Reading response (10 seconds)..."
timeout 10 cat $DEV

echo ""
echo "=== TEST COMPLETE ==="
echo ""
echo "Did you see a list of commands above?"
echo "If YES: CLI is working, Android app issue"
echo "If NO: Check Flipper settings (USB Channel, desktop screen)"
