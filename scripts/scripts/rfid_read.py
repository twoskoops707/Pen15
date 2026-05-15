#!/usr/bin/env python3
"""
REAL Flipper Zero RFID reader using pyflipper
Usage: python3 rfid_read.py [output_file]
"""
from flipperzero import FlipperZero
import sys
import os

def find_flipper_device():
    """Find Flipper Zero USB device path"""
    possible_paths = [
        '/dev/ttyACM0',
        '/dev/ttyACM1', 
        '/dev/ttyUSB0',
        '/dev/ttyUSB1',
        '/dev/bus/usb/001',
    ]
    
    for path in possible_paths:
        if os.path.exists(path):
            return path
    return None

def main():
    output_file = sys.argv[1] if len(sys.argv) > 1 else None
    
    try:
        # Find Flipper device
        device_path = find_flipper_device()
        if not device_path:
            print("ERROR|No Flipper Zero found. Connect via USB.")
            sys.exit(1)
        
        print(f"Connecting to Flipper at {device_path}...")
        flipper = FlipperZero(device_path)
        print("✓ Connected")

        print("Starting RFID reader...")
        print("Place 125kHz RFID card near Flipper antenna...")
        print("Waiting up to 15 seconds...")

        # Read RFID card with timeout
        result = flipper.rfid.read(timeout=15)

        if result:
            output = f"SUCCESS|{result.type}|{result.uid}"
            print(output)
            if output_file:
                with open(output_file, 'w') as f:
                    f.write(output)
            sys.exit(0)
        else:
            output = "ERROR|No card detected within timeout"
            print(output)
            if output_file:
                with open(output_file, 'w') as f:
                    f.write(output)
            sys.exit(1)

    except ImportError as e:
        error = f"ERROR|pyflipper not installed. Run: pip install pyflipper pyserial"
        print(error)
        if output_file:
            with open(output_file, 'w') as f:
                f.write(error)
        sys.exit(1)
    except Exception as e:
        error = f"ERROR|{str(e)}"
        print(error)
        if output_file:
            with open(output_file, 'w') as f:
                f.write(error)
        sys.exit(1)

if __name__ == "__main__":
    main()
