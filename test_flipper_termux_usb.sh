#!/data/data/com.termux/files/usr/bin/bash

echo "=== FLIPPER CLI TEST VIA TERMUX-USB ==="
echo ""
echo "This will request USB permission..."
echo ""

# Use termux-usb to access the device
termux-usb -r -e "
# Send help command
echo 'help' 

# Wait a bit
sleep 2

# Try to read response
timeout 3s cat || echo 'No response received'
" /dev/bus/usb/001/004

echo ""
echo "=== TEST COMPLETE ==="
