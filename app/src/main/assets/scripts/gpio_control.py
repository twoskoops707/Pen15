#!/usr/bin/env python3
"""
REAL GPIO control using pyflipper
"""
from flipperzero import FlipperZero
import sys

try:
    if len(sys.argv) < 4:
        print("ERROR|Usage: gpio_control.py <pin> <mode> <value>")
        sys.exit(1)

    pin = int(sys.argv[1])
    mode = sys.argv[2]  # input/output
    value = int(sys.argv[3]) if len(sys.argv) > 3 else 0

    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")

    print(f"Setting GPIO pin {pin} to {mode}...")

    # Configure GPIO
    flipper.gpio.set_mode(pin, mode)

    if mode == "output":
        flipper.gpio.write(pin, value)
        print(f"SUCCESS|Pin {pin} set to {value}")
    else:
        result = flipper.gpio.read(pin)
        print(f"SUCCESS|Pin {pin} reads {result}")

    sys.exit(0)

except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
