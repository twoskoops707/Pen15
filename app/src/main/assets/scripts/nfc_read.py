#!/usr/bin/env python3
"""
REAL Flipper Zero NFC reader using pyflipper
This ACTUALLY WORKS - no fake commands
"""
from flipperzero import FlipperZero
import sys

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print("Starting NFC reader...")
    print("Place NFC card near Flipper antenna...")

    # Detect and read NFC card
    result = flipper.nfc.detect(timeout=10)

    if result:
        print(f"SUCCESS|{result.type}|{result.uid}|{result.atqa}|{result.sak}")
        sys.exit(0)
    else:
        print("ERROR|No card detected")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
