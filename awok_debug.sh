#!/data/data/com.termux/files/usr/bin/bash

#######################################
# AWOK Mini V3 / ESP32 Marauder Debug Script
# Tests USB connection and ESP32 Marauder commands
#######################################

echo "======================================"
echo "AWOK Mini V3 / ESP32 MARAUDER DEBUG"
echo "======================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Check for USB devices
echo "[1/5] Checking for ESP32 devices..."
echo ""

ESP32_DEVICE=""
for dev in /dev/ttyUSB0 /dev/ttyUSB1 /dev/ttyACM0 /dev/ttyACM1; do
    if [ -e "$dev" ]; then
        echo -e "${GREEN}✓ Found device: $dev${NC}"
        ESP32_DEVICE="$dev"
        break
    fi
done

if [ -z "$ESP32_DEVICE" ]; then
    echo -e "${RED}✗ No USB devices found!${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "1. Connect AWOK Mini V3 via USB-C cable"
    echo "2. OR connect to Flipper Zero GPIO pins"
    echo "3. Check USB permissions in Android settings"
    echo "4. Try different USB cable"
    echo ""
    echo "Device detection:"
    ls -la /dev/tty* 2>/dev/null | grep -E "USB|ACM" || echo "No USB serial devices found"
    echo ""
    exit 1
fi

echo ""

# Step 2: Check permissions
echo "[2/5] Checking device permissions..."
if [ -r "$ESP32_DEVICE" ] && [ -w "$ESP32_DEVICE" ]; then
    echo -e "${GREEN}✓ Read/Write permissions OK${NC}"
else
    echo -e "${RED}✗ Permission denied${NC}"
    echo "Grant USB permission in Android popup"
    exit 1
fi

echo ""

# Step 3: Configure serial port
echo "[3/5] Configuring serial port..."
stty -F "$ESP32_DEVICE" 115200 cs8 -cstopb -parenb
echo -e "${GREEN}✓ Baud rate: 115200${NC}"

echo ""

# Step 4: Test ESP32 Marauder commands
echo "[4/5] Testing ESP32 Marauder commands..."
echo ""

echo "Sending: help"
echo "help" > "$ESP32_DEVICE"
sleep 1

# Try to read response
echo "Response:"
timeout 2 cat "$ESP32_DEVICE" 2>/dev/null | head -20 || echo "(No response - ESP32 may not echo)"

echo ""
echo "Testing WiFi scan command..."
echo "scan -t ap" > "$ESP32_DEVICE"
sleep 1

echo -e "${YELLOW}Check ESP32 screen for WiFi scan results${NC}"

echo ""

# Step 5: Interactive mode
echo "[5/5] Starting interactive mode..."
echo ""
echo "======================================"
echo "INTERACTIVE ESP32 MARAUDER CLI"
echo "======================================"
echo ""
echo "Device: $ESP32_DEVICE"
echo "Baud: 115200"
echo ""
echo "ESP32 Marauder Commands:"
echo ""
echo "WiFi Commands:"
echo "  scan -t ap              - Scan for access points"
echo "  scan -t sta             - Scan for stations/clients"
echo "  attack -t deauth        - Deauth attack"
echo "  attack -t beacon        - Beacon spam"
echo "  attack -t probe         - Probe request spam"
echo ""
echo "Bluetooth Commands:"
echo "  ble scan                - Scan BLE devices"
echo "  ble spam apple          - Apple BLE spam"
echo "  ble spam samsung        - Samsung BLE spam"
echo "  ble spam windows        - Windows Swift Pair spam"
echo ""
echo "Packet Sniffing:"
echo "  sniff -c 1              - Sniff channel 1"
echo "  sniff -c 6              - Sniff channel 6"
echo ""
echo "System:"
echo "  help                    - Show all commands"
echo "  reboot                  - Reboot ESP32"
echo "  list -s                 - List saved files"
echo ""
echo "Press Ctrl+C to exit"
echo "======================================"
echo ""

# Interactive loop
while true; do
    read -p "esp32> " cmd

    if [ -z "$cmd" ]; then
        continue
    fi

    # Send command
    echo "$cmd" > "$ESP32_DEVICE"

    # Wait for response
    sleep 0.5

    # Try to capture response (ESP32 Marauder shows output on screen mostly)
    timeout 2 cat "$ESP32_DEVICE" 2>/dev/null | head -30 || echo "(Command sent - check ESP32 screen for output)"
    echo ""
done
