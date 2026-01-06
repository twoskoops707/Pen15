#!/usr/bin/env python3
"""
Enhanced SubGHz receiver with amplifier support
Uses CC1101 amplifier for extended range (200+ meters)
"""
from flipperzero import FlipperZero
import sys

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")
    
    print("Initializing SubGHz amplifier...")
    print("Listening for signals (30 second timeout)...")
    
    # Enable amplifier mode if available
    result = flipper.subghz.receive_amplified(timeout=30)
    
    if result:
        print(f"SUCCESS|{result.frequency}|{result.protocol}|{result.data}|{result.rssi}")
        sys.exit(0)
    else:
        print("ERROR|No signal detected within timeout")
        sys.exit(1)
        
except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
