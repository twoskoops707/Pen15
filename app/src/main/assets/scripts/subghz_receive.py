#!/usr/bin/env python3
"""
REAL Sub-GHz receiver using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    frequency = float(sys.argv[1]) if len(sys.argv) > 1 else 433.92

    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print(f"Starting Sub-GHz receiver on {frequency} MHz...")
    print("Press button on remote...")

    # Receive Sub-GHz signal
    result = flipper.subghz.rx(frequency=frequency, timeout=15)

    if result:
        print(f"SUCCESS|{result.frequency}|{result.protocol}|{result.key}")
        sys.exit(0)
    else:
        print("ERROR|No signal detected")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
