package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class WiFiCaptureActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "WiFi Handshake Capture"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("WiFi Handshake Capture", includeTimestamp = false)
        logMessage("Captures WPA/WPA2 handshakes for offline cracking", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            val hasAirmonNg = ToolChecker.checkToolAvailable(this@WiFiCaptureActivity, "airmon-ng")
            val hasAirodumpNg = ToolChecker.checkToolAvailable(this@WiFiCaptureActivity, "airodump-ng")
            toolAvailable = hasAirmonNg && hasAirodumpNg

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
                logMessage("Captured handshakes can be cracked with:", includeTimestamp = false)
                logMessage("- Hashcat (GPU)", includeTimestamp = false)
                logMessage("- aircrack-ng (CPU)", includeTimestamp = false)
                logMessage("")
                logMessage("Output: ~/capture-01.cap", includeTimestamp = false)
                logMessage("")
                logMessage("⚠️ Requires monitor mode (may need root)", includeTimestamp = false)
            }
        }
    }

    private fun startCapture() {
        if (!toolAvailable) {
            logMessage("ERROR: aircrack-ng suite not available")
            return
        }

        clearOutput()
        logMessage("Starting handshake capture...")
        logMessage("")
        logMessage("Step 1: Enabling monitor mode on wlan0...")
        executeCommandWithProgress("airmon-ng start wlan0", useTermux = true)

        logMessage("")
        logMessage("Step 2: Starting packet capture...")
        logMessage("Scanning all channels...")
        logMessage("Output file: ~/capture-01.cap")
        logMessage("")
        logMessage("⚠️ Wait for a device to connect to see WPA handshake")
        logMessage("Press Ctrl+C to stop capture")
        logMessage("")

        executeCommandWithProgress("airodump-ng -w ~/capture --output-format cap wlan0mon", useTermux = true)

        logMessage("")
        logMessage("✓ Capture stopped!")
        logMessage("")
        logMessage("Crack with:")
        logMessage("aircrack-ng -w wordlist.txt ~/capture-01.cap", includeTimestamp = false)
        logMessage("")
        logMessage("Or use Hashcat for GPU acceleration", includeTimestamp = false)
        logMessage("")
        logMessage("To stop monitor mode, run:")
        logMessage("airmon-ng stop wlan0mon", includeTimestamp = false)
    }
}
