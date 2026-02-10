#!/data/data/com.termux/files/usr/bin/bash
# Setup script for pyflipper installation in Termux

echo "=== Setting up pyflipper for Flipper Zero ==="
echo ""

# Update packages
echo "[1/4] Updating packages..."
pkg update -y

# Install Python and dependencies
echo "[2/4] Installing Python and dependencies..."
pkg install -y python python-pip git

# Install pyflipper and pyserial
echo "[3/4] Installing pyflipper..."
pip install --upgrade pyflipper pyserial

# Create scripts directory
echo "[4/4] Creating scripts directory..."
mkdir -p /data/data/com.termux/files/home/Pen15/scripts

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Test connection:"
echo "  python3 -c \"from flipperzero import FlipperZero; print('pyflipper OK')\""
echo ""
echo "List USB devices:"
echo "  ls -la /dev/tty*"
echo ""
