#!/data/data/com.termux/files/usr/bin/bash
# Install pyflipper and dependencies
# This ACTUALLY installs the real library

echo "=== Installing pyflipper for Flipper Zero ==="
echo ""

# Install Python if not present
if ! command -v python &> /dev/null; then
    echo "Installing Python..."
    pkg install -y python
fi

# Install pip packages
echo "Installing pyflipper..."
pip install --upgrade pyflipper pyserial

echo ""
echo "✓ Installation complete"
echo ""
echo "Testing connection to Flipper..."
python3 -c "from flipperzero import FlipperZero; f = FlipperZero.create(); print('✓ Flipper Zero detected')"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓✓✓ ALL GOOD - Flipper is ready! ✓✓✓"
else
    echo ""
    echo "✗ Could not connect to Flipper"
    echo "Make sure Flipper is connected via USB"
fi
