#!/data/data/com.termux/files/usr/bin/bash

echo "=== FLIPPER ZERO USB CONNECTION TEST ==="
echo ""

# Find Flipper device
echo "1. Looking for Flipper Zero USB device..."
DEVICE=""
for dev in /dev/ttyACM* /dev/ttyUSB*; do
    if [ -e "$dev" ]; then
        echo "   Found: $dev"
        DEVICE="$dev"
        break
    fi
done

if [ -z "$DEVICE" ]; then
    echo "   ERROR: No USB serial device found!"
    echo ""
    echo "   Make sure:"
    echo "   - Flipper is connected via USB-C"
    echo "   - USB debugging is enabled"
    echo "   - You granted USB permissions"
    exit 1
fi

echo "   Using device: $DEVICE"
echo ""

# Check if we can access the device
echo "2. Checking device permissions..."
if [ ! -r "$DEVICE" ] || [ ! -w "$DEVICE" ]; then
    echo "   ERROR: No read/write permission on $DEVICE"
    echo "   Try: termux-usb -r $DEVICE"
    exit 1
fi
echo "   OK: Can read/write to device"
echo ""

# Configure serial port settings
echo "3. Configuring serial port (115200 baud, 8N1)..."
stty -F $DEVICE 115200 cs8 -cstopb -parenb raw -echo
echo "   OK: Port configured"
echo ""

# Test 1: Send newline and see if we get prompt
echo "4. Test 1: Checking for CLI prompt..."
echo "" > $DEVICE
sleep 1

# Try to read response
echo "5. Test 2: Sending 'help' command..."
echo "help" > $DEVICE
sleep 2

# Read any available data
echo "6. Reading response from Flipper..."
timeout 3s cat $DEVICE &
CAT_PID=$!
sleep 3
kill $CAT_PID 2>/dev/null

echo ""
echo "7. Test 3: Trying alternative commands..."
echo "?" > $DEVICE
sleep 1
echo "info" > $DEVICE
sleep 1
echo "device_info" > $DEVICE
sleep 1

echo ""
echo "8. Reading responses..."
timeout 3s cat $DEVICE

echo ""
echo ""
echo "=== TEST COMPLETE ==="
echo ""
echo "What to check:"
echo "1. Did you see ANY text output from Flipper?"
echo "2. Did you see a '>' prompt?"
echo "3. Did you see command responses?"
echo ""
echo "If you saw NOTHING:"
echo "  - Flipper might need to be unlocked (enter PIN)"
echo "  - Flipper might be in app, not desktop"
echo "  - Firmware might not have CLI enabled"
echo "  - Wrong baud rate or settings"
echo ""
echo "Next steps:"
echo "1. Make sure Flipper is at DESKTOP screen (not in an app)"
echo "2. Make sure Flipper is UNLOCKED"
echo "3. Try unplugging and replugging USB"
echo "4. Check Flipper screen - does it say 'USB Connected'?"
