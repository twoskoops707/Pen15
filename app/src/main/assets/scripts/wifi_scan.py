#!/usr/bin/env python3
"""
WiFi Scanner using AWOK Mini V3 via pyflipper
Scans for WiFi networks and logs SSID, BSSID, channel, signal strength
"""
from flipperzero import FlipperZero
import sys
import json
from datetime import datetime

try:
    print("Connecting to Flipper Zero...")
    flipper = FlipperZero.create()
    print("✓ Connected")
    
    print("Starting WiFi scan via AWOK Mini V3...")
    print("This may take 10-30 seconds...")
    
    # Scan for WiFi networks
    networks = flipper.wifi.scan(timeout=30)
    
    if networks:
        print(f"SUCCESS|{len(networks)}")
        for net in networks:
            # Format: SSID|BSSID|Channel|RSSI|Security
            print(f"NETWORK|{net.ssid}|{net.bssid}|{net.channel}|{net.rssi}|{net.security}")
        sys.exit(0)
    else:
        print("ERROR|No WiFi networks found")
        sys.exit(1)
        
except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
