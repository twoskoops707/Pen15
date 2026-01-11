package com.android.pen15.ui.utilities

import android.os.Bundle
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity

class ScriptBuilderActivity : BaseToolActivity() {

    override fun getToolName() = "Script Builder"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { buildScript() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Bash Script Builder", includeTimestamp = false)
        logMessage("Create automation scripts for pentesting", includeTimestamp = false)
        logMessage("")
        logMessage("Click Execute to see example scripts", includeTimestamp = false)
    }

    private fun buildScript() {
        clearOutput()

        logMessage("=== SCRIPT TEMPLATES ===", includeTimestamp = false)
        logMessage("")

        logMessage("--- Network Scanner Script ---", includeTimestamp = false)
        logMessage("#!/bin/bash", includeTimestamp = false)
        logMessage("echo \"Starting network scan...\"", includeTimestamp = false)
        logMessage("nmap -sn 192.168.1.0/24 -oN scan_results.txt", includeTimestamp = false)
        logMessage("cat scan_results.txt | grep \"report for\" | cut -d\" \" -f5", includeTimestamp = false)
        logMessage("echo \"Scan complete!\"", includeTimestamp = false)
        logMessage("")
        logMessage("Save as: ~/scan_network.sh", includeTimestamp = false)
        logMessage("")

        logMessage("--- WiFi Attack Automation ---", includeTimestamp = false)
        logMessage("#!/bin/bash", includeTimestamp = false)
        logMessage("IFACE=wlan0", includeTimestamp = false)
        logMessage("TARGET_BSSID=\"XX:XX:XX:XX:XX:XX\"", includeTimestamp = false)
        logMessage("", includeTimestamp = false)
        logMessage("airmon-ng check kill", includeTimestamp = false)
        logMessage("airmon-ng start $IFACE", includeTimestamp = false)
        logMessage("airodump-ng -c 6 --bssid $TARGET_BSSID \\", includeTimestamp = false)
        logMessage("  -w capture ${IFACE}mon", includeTimestamp = false)
        logMessage("")
        logMessage("Save as: ~/wifi_capture.sh", includeTimestamp = false)
        logMessage("")

        logMessage("--- Port Scanner Script ---", includeTimestamp = false)
        logMessage("#!/bin/bash", includeTimestamp = false)
        logMessage("TARGET=$1", includeTimestamp = false)
        logMessage("if [ -z \"$TARGET\" ]; then", includeTimestamp = false)
        logMessage("  echo \"Usage: $0 <target_ip>\"", includeTimestamp = false)
        logMessage("  exit 1", includeTimestamp = false)
        logMessage("fi", includeTimestamp = false)
        logMessage("", includeTimestamp = false)
        logMessage("echo \"Scanning $TARGET...\"", includeTimestamp = false)
        logMessage("nmap -p- -T4 -A $TARGET -oN ${TARGET}_scan.txt", includeTimestamp = false)
        logMessage("echo \"Results saved to ${TARGET}_scan.txt\"", includeTimestamp = false)
        logMessage("")
        logMessage("Save as: ~/port_scan.sh", includeTimestamp = false)
        logMessage("Usage: ./port_scan.sh 192.168.1.1", includeTimestamp = false)
        logMessage("")

        logMessage("--- Hash Cracking Automation ---", includeTimestamp = false)
        logMessage("#!/bin/bash", includeTimestamp = false)
        logMessage("HASHFILE=$1", includeTimestamp = false)
        logMessage("WORDLIST_URL=\"https://raw.githubusercontent.com/\"", includeTimestamp = false)
        logMessage("WORDLIST_URL+=\"danielmiessler/SecLists/master/\"", includeTimestamp = false)
        logMessage("WORDLIST_URL+=\"Passwords/darkweb2017-top10000.txt\"", includeTimestamp = false)
        logMessage("", includeTimestamp = false)
        logMessage("curl -L -o wordlist.txt \"$WORDLIST_URL\"", includeTimestamp = false)
        logMessage("hashcat -m 0 -a 0 $HASHFILE wordlist.txt", includeTimestamp = false)
        logMessage("rm wordlist.txt", includeTimestamp = false)
        logMessage("")
        logMessage("Save as: ~/crack_hash.sh", includeTimestamp = false)
        logMessage("")

        logMessage("--- To Create Scripts ---", includeTimestamp = false)
        logMessage("1. Copy desired template", includeTimestamp = false)
        logMessage("2. Create file: nano ~/script.sh", includeTimestamp = false)
        logMessage("3. Paste and customize", includeTimestamp = false)
        logMessage("4. Make executable: chmod +x ~/script.sh", includeTimestamp = false)
        logMessage("5. Run: ./script.sh", includeTimestamp = false)
    }
}
