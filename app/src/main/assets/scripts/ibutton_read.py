#!/usr/bin/env python3
"""
REAL iButton reader using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print("Starting iButton reader...")
    print("Touch iButton key to Flipper...")

    # Read iButton key
    result = flipper.ibutton.read(timeout=10)

    if result:
        print(f"SUCCESS|{result.type}|{result.key}")
        sys.exit(0)
    else:
        print("ERROR|No key detected within timeout")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
