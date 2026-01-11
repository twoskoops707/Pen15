package com.android.pen15.ui.crypto

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class AircrackActivity : BaseToolActivity() {

    private var toolAvailable = false

    // Popular online wordlist URLs for WiFi cracking
    private val wordlistUrls = mapOf(
        "common-wifi (10K)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/WiFi-WPA/probable-v2-wpa-top4800.txt",
        "rockyou (14M)" to "https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt",
        "darkweb2017 (10K)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/darkweb2017-top10000.txt",
        "xato-net (10K)" to "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/xato-net-10-million-passwords-10000.txt"
    )

    override fun getToolName() = "WiFi Cracker (Aircrack-ng)"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startCracking() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("WiFi Cracker (Aircrack-ng)", includeTimestamp = false)
        logMessage("Crack WPA/WPA2 handshake files", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@AircrackActivity, "aircrack-ng")
            if (!toolAvailable) {
                logMessage("⚠️ aircrack-ng not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("aircrack-ng")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ aircrack-ng is available", includeTimestamp = false)
                logMessage("")
                logMessage("--- Online Wordlists Available ---", includeTimestamp = false)
                wordlistUrls.forEach { (name, url) ->
                    logMessage("• $name", includeTimestamp = false)
                }
                logMessage("")
                logMessage("Prerequisites:", includeTimestamp = false)
                logMessage("1. Capture handshake file (.cap)", includeTimestamp = false)
                logMessage("2. Use WiFi Handshake Capture tool", includeTimestamp = false)
                logMessage("")
                logMessage("This tool will:", includeTimestamp = false)
                logMessage("• Download online wordlist", includeTimestamp = false)
                logMessage("• Crack WPA/WPA2 handshake", includeTimestamp = false)
                logMessage("• Display password if found", includeTimestamp = false)
            }
        }
    }

    private fun startCracking() {
        if (!toolAvailable) {
            logMessage("ERROR: aircrack-ng not available")
            return
        }

        clearOutput()
        logMessage("Starting WiFi password cracking...")
        logMessage("")

        // Download WiFi-specific wordlist
        val wordlistUrl = wordlistUrls["common-wifi (10K)"]!!
        logMessage("Downloading WiFi wordlist...")
        logMessage("URL: $wordlistUrl")
        logMessage("")

        executeCommandWithProgress("curl -L -o ~/wifi-wordlist.txt '$wordlistUrl'", useTermux = true)

        logMessage("")
        logMessage("✓ Wordlist downloaded: ~/wifi-wordlist.txt")
        logMessage("")
        logMessage("Now crack your capture file:")
        logMessage("")
        logMessage("aircrack-ng -w ~/wifi-wordlist.txt ~/capture-01.cap", includeTimestamp = false)
        logMessage("")
        logMessage("Example with larger wordlist (rockyou):")
        logMessage("curl -L -o ~/rockyou.txt '${wordlistUrls["rockyou (14M)"]}'", includeTimestamp = false)
        logMessage("aircrack-ng -w ~/rockyou.txt ~/capture-01.cap", includeTimestamp = false)
        logMessage("")
        logMessage("⚠️ Note: Cracking can take hours/days", includeTimestamp = false)
        logMessage("   depending on password complexity", includeTimestamp = false)
        logMessage("")
        logMessage("More WiFi wordlists:", includeTimestamp = false)
        logMessage("https://github.com/danielmiessler/SecLists/tree/master/Passwords/WiFi-WPA", includeTimestamp = false)
    }
}
