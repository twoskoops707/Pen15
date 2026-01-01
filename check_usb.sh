#!/data/data/com.termux/files/usr/bin/bash

echo "=== USB DEVICE INFORMATION ===" 
echo ""

# Check if termux-usb is available
if ! command -v termux-usb &> /dev/null; then
    echo "ERROR: termux-api not installed!"
    echo "Install with: pkg install termux-api"
    exit 1
fi

# Get USB device info
USB_DEV=$(termux-usb -l | grep -o '/dev/bus/usb/[0-9]*/[0-9]*' | head -1)

if [ -z "$USB_DEV" ]; then
    echo "No USB device found!"
    echo ""
    echo "Make sure:"
    echo "1. Flipper is connected via USB-C"
    echo "2. Flipper screen shows 'USB Connected'"
    echo "3. You granted USB permission in Android"
    exit 1
fi

echo "Found USB device: $USB_DEV"
echo ""
echo "Getting device information..."
echo ""

# Request USB permission and get info
termux-usb -r -e "lsusb" $USB_DEV

echo ""
echo "=== WHAT TO LOOK FOR ===" 
echo "Vendor ID (VID) should be: 0483"
echo "Product ID (PID) should be: 5740"
echo ""
echo "If VID/PID don't match, the Android app won't recognize it!"
