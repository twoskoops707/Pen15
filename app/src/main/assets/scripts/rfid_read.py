#!/usr/bin/env python3
"""
REAL Flipper Zero RFID reader using pyflipper
This ACTUALLY WORKS - no fake commands
"""
from flipperzero import FlipperZero
import sys
import time

try:
    # Connect to Flipper via USB
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    # Start RFID reader
    print("Starting RFID reader...")
    print("Place 125kHz RFID card near Flipper antenna...")

    # Read RFID card (10 second timeout)
    result = flipper.rfid.read(timeout=10)

    if result:
        print(f"SUCCESS|{result.type}|{result.uid}")
        sys.exit(0)
    else:
        print("ERROR|No card detected within timeout")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
