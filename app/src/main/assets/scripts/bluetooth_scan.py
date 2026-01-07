#!/usr/bin/env python3
"""
REAL Bluetooth scanner using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print("Starting Bluetooth scan...")

    # Scan for Bluetooth devices
    devices = flipper.bluetooth.scan(timeout=10)

    if devices:
        for device in devices:
            print(f"DEVICE|{device.name}|{device.address}|{device.rssi}")
        print("SUCCESS|Scan complete")
        sys.exit(0)
    else:
        print("ERROR|No devices found")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
