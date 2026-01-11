package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class BluetoothScannerActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "Bluetooth Scanner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Bluetooth BLE Scanner", includeTimestamp = false)
        logMessage("Scan for nearby Bluetooth devices", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@BluetoothScannerActivity, "hcitool")
            if (!toolAvailable) {
                logMessage("⚠️ hcitool not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("hcitool")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ hcitool is available", includeTimestamp = false)
                logMessage("")
                logMessage("Will scan for Classic and BLE devices", includeTimestamp = false)
            }
        }
    }

    private fun startScan() {
        if (!toolAvailable) {
            logMessage("ERROR: hcitool not available")
            return
        }

        clearOutput()
        logMessage("Scanning for Bluetooth devices...")
        logMessage("This will take about 10 seconds...")
        logMessage("")

        // Classic Bluetooth scan
        logMessage("--- Classic Bluetooth Scan ---")
        executeCommandWithProgress("hcitool scan", useTermux = true)

        logMessage("")
        logMessage("--- BLE (Low Energy) Scan ---")
        logMessage("Press Ctrl+C to stop BLE scan")
        executeCommandWithProgress("timeout 10 hcitool lescan", useTermux = true)

        logMessage("")
        logMessage("✓ Scan complete!")
    }
}
