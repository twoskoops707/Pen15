#!/usr/bin/env python3
"""
REAL Infrared learner using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print("Starting IR learning mode...")
    print("Point remote at Flipper and press button...")

    # Learn IR signal
    result = flipper.infrared.learn(timeout=15)

    if result:
        print(f"SUCCESS|{result.protocol}|{result.address}|{result.command}")
        sys.exit(0)
    else:
        print("ERROR|No signal detected")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
