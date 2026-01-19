#!/usr/bin/env python3
"""
Flipper Zero CLI Interface - Permanent Connection
No timeout - stays connected until you disconnect
Uses pyserial for USB CDC communication

Usage:
    python flipper_cli.py [command]

Commands:
    shell       - Interactive shell (default)
    rfid        - Read RFID card
    nfc         - Detect NFC card
    subghz      - Sub-GHz scanner
    ir          - Infrared receiver
    ikey        - iButton reader
    storage     - List SD card files
    info        - Device info
"""

import serial
import serial.tools.list_ports
import sys
import time
import threading
import os

class FlipperCLI:
    def __init__(self):
        self.serial = None
        self.connected = False
        self.running = True
        self.response_buffer = ""

    def find_flipper(self):
        """Find Flipper Zero USB port"""
        ports = serial.tools.list_ports.comports()
        for port in ports:
            # Flipper Zero VID:PID = 0483:5740
            if port.vid == 0x0483 and port.pid == 0x5740:
                return port.device
            # Also check by name
            if "flipper" in port.description.lower():
                return port.device
            # Common paths on Android/Termux
            if "ttyACM" in port.device or "ttyUSB" in port.device:
                return port.device

        # Try common paths directly
        for path in ["/dev/ttyACM0", "/dev/ttyACM1", "/dev/ttyUSB0"]:
            if os.path.exists(path):
                return path
        return None

    def connect(self):
        """Connect to Flipper Zero - NO TIMEOUT"""
        port = self.find_flipper()
        if not port:
            print("ERROR: Flipper Zero not found")
            print("Make sure it's connected via USB-C")
            return False

        try:
            # 115200 baud, no timeout (blocking reads)
            self.serial = serial.Serial(
                port=port,
                baudrate=115200,
                bytesize=serial.EIGHTBITS,
                parity=serial.PARITY_NONE,
                stopbits=serial.STOPBITS_ONE,
                timeout=None,  # PERMANENT CONNECTION - no timeout
                write_timeout=5,
                dsrdtr=True,   # Enable DTR
                rtscts=False
            )

            # Set DTR high to signal we're ready
            self.serial.dtr = True
            self.serial.rts = True

            time.sleep(0.5)

            # Flush input buffer
            self.serial.reset_input_buffer()

            # Send newline to wake CLI
            self.serial.write(b"\r\n")
            time.sleep(0.3)

            # Read any welcome message
            if self.serial.in_waiting:
                self.serial.read(self.serial.in_waiting)

            self.connected = True
            print(f"CONNECTED to {port}")
            print("=" * 40)
            return True

        except Exception as e:
            print(f"ERROR: {e}")
            return False

    def send_command(self, cmd, timeout=10):
        """Send command and get response"""
        if not self.connected:
            return "Not connected"

        try:
            # Clear buffer
            self.serial.reset_input_buffer()

            # Send command with CR
            self.serial.write(f"{cmd}\r".encode())
            self.serial.flush()

            # Read response until prompt
            response = ""
            start = time.time()

            while time.time() - start < timeout:
                if self.serial.in_waiting:
                    chunk = self.serial.read(self.serial.in_waiting).decode('utf-8', errors='ignore')
                    response += chunk

                    # Check for prompt (command complete)
                    if ">:" in response or response.strip().endswith(">"):
                        break
                time.sleep(0.05)

            # Clean response
            lines = response.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            clean = []
            for line in lines:
                line = line.strip()
                if line and line != cmd and not line.startswith(">:") and line != ">":
                    clean.append(line)

            return "\n".join(clean) if clean else "(no response)"

        except Exception as e:
            return f"Error: {e}"

    def disconnect(self):
        """Disconnect from Flipper"""
        self.connected = False
        if self.serial:
            try:
                self.serial.close()
            except:
                pass
        print("Disconnected")

    # ==================
    # PENTESTING TOOLS
    # ==================

    def read_rfid(self):
        """Read 125kHz RFID card"""
        print("\n" + "=" * 40)
        print("  RFID READER (125kHz)")
        print("=" * 40)
        print("Place card on Flipper...")
        print()

        result = self.send_command("rfid read", timeout=30)

        # Parse and display nicely
        if "EM4100" in result or "HID" in result or "Key:" in result:
            print("✓ CARD DETECTED!")
            print("-" * 40)
            print(result)
            print("-" * 40)
        else:
            print(result)

        return result

    def detect_nfc(self):
        """Detect NFC card"""
        print("\n" + "=" * 40)
        print("  NFC DETECTOR")
        print("=" * 40)
        print("Place NFC card/tag on Flipper...")
        print()

        result = self.send_command("nfc detect", timeout=30)

        if "UID" in result or "ATQA" in result or "SAK" in result:
            print("✓ NFC TAG DETECTED!")
            print("-" * 40)
            # Extract key info
            for line in result.split("\n"):
                if any(x in line for x in ["UID", "ATQA", "SAK", "Type"]):
                    print(f"  {line}")
            print("-" * 40)
        else:
            print(result)

        return result

    def scan_subghz(self, freq=433920000):
        """Scan Sub-GHz frequency"""
        print("\n" + "=" * 40)
        print(f"  SUB-GHZ SCANNER ({freq/1000000:.2f} MHz)")
        print("=" * 40)
        print("Listening for signals...")
        print("Press Ctrl+C to stop")
        print()

        result = self.send_command(f"subghz rx {freq}", timeout=60)
        print(result)
        return result

    def read_ibutton(self):
        """Read iButton key"""
        print("\n" + "=" * 40)
        print("  iBUTTON READER")
        print("=" * 40)
        print("Touch iButton to Flipper...")
        print()

        result = self.send_command("ikey read", timeout=30)

        if "Key:" in result or "ID:" in result:
            print("✓ iBUTTON DETECTED!")
            print("-" * 40)
            print(result)
            print("-" * 40)
        else:
            print(result)

        return result

    def receive_ir(self):
        """Receive infrared signal"""
        print("\n" + "=" * 40)
        print("  INFRARED RECEIVER")
        print("=" * 40)
        print("Point remote at Flipper, press button...")
        print()

        result = self.send_command("ir rx", timeout=30)
        print(result)
        return result

    def list_storage(self, path="/ext"):
        """List files on SD card"""
        print("\n" + "=" * 40)
        print(f"  SD CARD: {path}")
        print("=" * 40)

        result = self.send_command(f"storage list {path}", timeout=10)

        if result and result != "(no response)":
            for line in result.split("\n"):
                if "[D]" in line:
                    print(f"  📁 {line.replace('[D]', '').strip()}")
                elif "[F]" in line:
                    print(f"  📄 {line.replace('[F]', '').strip()}")
                else:
                    print(f"     {line}")
        else:
            print("(empty or error)")

        return result

    def read_file(self, path):
        """Read file from SD card"""
        print(f"\n--- Reading: {path} ---")
        result = self.send_command(f"storage read {path}", timeout=10)
        print(result)
        return result

    def device_info(self):
        """Get device information"""
        print("\n" + "=" * 40)
        print("  DEVICE INFO")
        print("=" * 40)

        result = self.send_command("device_info", timeout=5)
        print(result)
        return result

    def interactive_shell(self):
        """Interactive CLI shell"""
        print("\n" + "=" * 40)
        print("  FLIPPER ZERO CLI SHELL")
        print("=" * 40)
        print("Type commands directly. Type 'exit' to quit.")
        print("Type 'help' or '?' for command list.")
        print()

        while self.connected:
            try:
                cmd = input("flipper> ").strip()

                if cmd.lower() in ['exit', 'quit', 'q']:
                    break
                elif cmd == "":
                    continue

                result = self.send_command(cmd)
                print(result)
                print()

            except KeyboardInterrupt:
                print("\nUse 'exit' to quit")
            except EOFError:
                break


def main():
    flipper = FlipperCLI()

    if not flipper.connect():
        sys.exit(1)

    try:
        # Parse command line argument
        if len(sys.argv) > 1:
            cmd = sys.argv[1].lower()

            if cmd == "rfid":
                flipper.read_rfid()
            elif cmd == "nfc":
                flipper.detect_nfc()
            elif cmd == "subghz":
                freq = int(sys.argv[2]) if len(sys.argv) > 2 else 433920000
                flipper.scan_subghz(freq)
            elif cmd == "ir":
                flipper.receive_ir()
            elif cmd == "ikey" or cmd == "ibutton":
                flipper.read_ibutton()
            elif cmd == "storage":
                path = sys.argv[2] if len(sys.argv) > 2 else "/ext"
                flipper.list_storage(path)
            elif cmd == "info":
                flipper.device_info()
            elif cmd == "shell":
                flipper.interactive_shell()
            else:
                # Direct command
                result = flipper.send_command(" ".join(sys.argv[1:]))
                print(result)
        else:
            # Default: interactive shell
            flipper.interactive_shell()

    except KeyboardInterrupt:
        print("\nInterrupted")
    finally:
        flipper.disconnect()


if __name__ == "__main__":
    main()
