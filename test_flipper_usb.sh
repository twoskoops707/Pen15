#!/data/data/com.termux/files/usr/bin/bash

# Test Flipper Zero USB connection from Termux
echo "=== FLIPPER ZERO USB TEST ==="
echo ""
echo "1. Checking USB devices via Android..."

# Check if termux-api is installed
if command -v termux-usb-list &> /dev/null; then
    echo "Termux-API found - listing USB devices:"
    termux-usb-list
else
    echo "termux-api NOT installed"
    echo ""
    echo "To install:"
    echo "  pkg install termux-api"
    echo "  (Also install Termux:API app from F-Droid/Play Store)"
fi

echo ""
echo "2. Looking for serial devices in /dev..."
ls -la /dev/tty* 2>/dev/null | grep -E "(ACM|USB|serial)" || echo "  No USB serial devices found"

echo ""
echo "3. Checking Android USB via logcat..."
timeout 2 logcat -d | grep -i "flipper\|usb.*0483\|usb.*5740" | tail -5 || echo "  No Flipper USB logs found"

echo ""
echo "=== CONCLUSION ==="
echo "Android apps can access USB via UsbManager API"
echo "Termux cannot directly access USB serial without root or Termux:API"
echo ""
echo "The app SHOULD be able to connect to Flipper."
echo "If not working, check:"
echo "1. USB-C cable is data cable (not charge-only)"
echo "2. Flipper is powered on"
echo "3. Grant USB permission when app requests it"
echo "4. Check app logs for errors"
