package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class NetworkScannerActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "Network Scanner (Nmap)"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Nmap Network Scanner", includeTimestamp = false)
        logMessage("Scan local network for devices and open ports", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@NetworkScannerActivity, "nmap")
            if (!toolAvailable) {
                logMessage("⚠️ Nmap not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("nmap")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ Nmap is available", includeTimestamp = false)
                logMessage("")
                logMessage("Usage: Enter target network (e.g., 192.168.1.0/24)", includeTimestamp = false)
            }
        }
    }

    private fun startScan() {
        if (!toolAvailable) {
            logMessage("ERROR: Nmap not available")
            return
        }

        clearOutput()
        logMessage("Starting network scan...")
        logMessage("Discovering devices on 192.168.1.0/24...")
        logMessage("This may take several minutes...")

        // Quick host discovery
        executeCommandWithProgress("nmap -sn 192.168.1.0/24", useTermux = true)
    }
}
