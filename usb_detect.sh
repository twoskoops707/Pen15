#!/data/data/com.termux/files/usr/bin/bash

echo "======================================"
echo "USB DEVICE DETECTION TOOL"
echo "======================================"
echo ""

echo "[1] Listing ALL /dev/tty* devices:"
ls -la /dev/tty* 2>/dev/null | grep -E "USB|ACM|ttyS" || echo "No USB/serial devices found in /dev"

echo ""
echo "[2] Checking for USB devices specifically:"
ls -la /dev/ttyUSB* /dev/ttyACM* 2>/dev/null || echo "No /dev/ttyUSB* or /dev/ttyACM* devices"

echo ""
echo "[3] Checking Termux USB permission:"
termux-usb -l 2>/dev/null || echo "termux-usb not available or no devices"

echo ""
echo "[4] Checking for any connected USB devices (lsusb):"
if command -v lsusb &> /dev/null; then
    lsusb
else
    echo "lsusb not installed. Installing..."
    pkg install -y usbutils
    lsusb
fi

echo ""
echo "[5] Android USB device listing:"
ls -la /sys/bus/usb/devices/ 2>/dev/null || echo "Cannot access USB devices"

echo ""
echo "======================================"
echo "NEXT STEPS:"
echo "======================================"
echo ""
echo "If no devices found:"
echo "1. IS FLIPPER CONNECTED? Check physical USB-C connection"
echo "2. ON FLIPPER: GPIO → USB-UART Bridge → Channel 0"
echo "3. Grant USB permission when Android popup appears"
echo "4. Try running: termux-usb -l"
echo "5. Try: termux-usb -r /dev/bus/usb/XXX/XXX (from lsusb output)"
echo ""
echo "If devices found but permission denied:"
echo "1. Run: termux-usb -r <device_path>"
echo "2. Grant permission in popup"
echo ""
