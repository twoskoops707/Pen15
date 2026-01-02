#!/data/data/com.termux/files/usr/bin/bash

#######################################
# Flipper Zero USB Debug Script
# Tests USB connection and command execution
#######################################

echo "======================================"
echo "FLIPPER ZERO USB DEBUG SCRIPT"
echo "======================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Check for USB devices
echo "[1/6] Checking for USB devices..."
echo ""

FLIPPER_DEVICE=""
for dev in /dev/ttyACM0 /dev/ttyACM1 /dev/ttyUSB0 /dev/ttyUSB1; do
    if [ -e "$dev" ]; then
        echo -e "${GREEN}✓ Found device: $dev${NC}"
        FLIPPER_DEVICE="$dev"
        break
    fi
done

if [ -z "$FLIPPER_DEVICE" ]; then
    echo -e "${RED}✗ No USB devices found!${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "1. Connect Flipper Zero via USB-C cable"
    echo "2. Enable 'USB-UART Bridge' on Flipper:"
    echo "   GPIO → USB-UART Bridge → Channel 0"
    echo "3. Check USB permissions in Android settings"
    echo "4. Try different USB cable"
    echo ""
    exit 1
fi

echo ""

# Step 2: Check permissions
echo "[2/6] Checking device permissions..."
if [ -r "$FLIPPER_DEVICE" ] && [ -w "$FLIPPER_DEVICE" ]; then
    echo -e "${GREEN}✓ Read/Write permissions OK${NC}"
else
    echo -e "${RED}✗ Permission denied${NC}"
    echo "Grant USB permission in Android popup"
    exit 1
fi

echo ""

# Step 3: Check Termux packages
echo "[3/6] Checking required packages..."
if command -v screen &> /dev/null; then
    echo -e "${GREEN}✓ screen installed${NC}"
else
    echo -e "${YELLOW}! screen not installed${NC}"
    echo "Installing screen..."
    pkg install -y screen
fi

echo ""

# Step 4: Test basic connection
echo "[4/6] Testing basic serial connection..."
echo ""
echo "Sending 'help' command to Flipper..."
echo "help" > "$FLIPPER_DEVICE"
sleep 1

# Try to read response
if timeout 2 cat "$FLIPPER_DEVICE" 2>/dev/null | head -5; then
    echo ""
    echo -e "${GREEN}✓ Flipper responding!${NC}"
else
    echo -e "${YELLOW}! No response (this might be normal)${NC}"
    echo "Flipper may not echo commands back"
fi

echo ""

# Step 5: Test Flipper CLI commands
echo "[5/6] Testing Flipper CLI commands..."
echo ""

# Test device info
echo "Testing: device_info"
echo "device_info" > "$FLIPPER_DEVICE"
sleep 1

# Test LED
echo "Testing: led r 255 (Red LED)"
echo "led r 255" > "$FLIPPER_DEVICE"
sleep 1
echo "led r 0" > "$FLIPPER_DEVICE"

echo -e "${GREEN}✓ Check Flipper screen - LED should have blinked red${NC}"
echo ""

# Step 6: Interactive mode
echo "[6/6] Starting interactive mode..."
echo ""
echo "======================================"
echo "INTERACTIVE FLIPPER CLI"
echo "======================================"
echo ""
echo "Device: $FLIPPER_DEVICE"
echo ""
echo "Useful commands:"
echo "  help              - Show all commands"
echo "  device_info       - Show device info"
echo "  led r 255         - Red LED on"
echo "  led g 255         - Green LED on"
echo "  led b 255         - Blue LED on"
echo "  led r 0           - LED off"
echo "  loader list       - List installed apps"
echo "  loader open NFC   - Open NFC app"
echo "  rfid read         - Read RFID card"
echo "  nfc detect        - Detect NFC tag"
echo ""
echo "Press Ctrl+C to exit"
echo "======================================"
echo ""

# Interactive loop
while true; do
    read -p "flipper> " cmd

    if [ -z "$cmd" ]; then
        continue
    fi

    echo "$cmd" > "$FLIPPER_DEVICE"

    # Try to capture response
    timeout 1 cat "$FLIPPER_DEVICE" 2>/dev/null | head -10 || echo "(Command sent - check Flipper screen)"
    echo ""
done
