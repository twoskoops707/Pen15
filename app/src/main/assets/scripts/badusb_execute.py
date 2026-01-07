#!/usr/bin/env python3
"""
REAL BadUSB script executor using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    if len(sys.argv) < 2:
        print("ERROR|Script path required")
        sys.exit(1)

    script_path = sys.argv[1]

    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print(f"Executing BadUSB script: {script_path}")

    # Execute BadUSB script
    result = flipper.badusb.run(script_path)

    if result:
        print(f"SUCCESS|Script executed")
        sys.exit(0)
    else:
        print("ERROR|Script execution failed")
        sys.exit(1)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
