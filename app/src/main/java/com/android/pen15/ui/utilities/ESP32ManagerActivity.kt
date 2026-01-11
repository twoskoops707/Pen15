package com.android.pen15.ui.utilities

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class ESP32ManagerActivity : BaseToolActivity() {

    override fun getToolName() = "ESP32 WiFi Manager"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { manageESP32() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("ESP32 WiFi Manager", includeTimestamp = false)
        logMessage("Manage ESP32 Marauder & WiFi attacks", includeTimestamp = false)
        logMessage("")
        logMessage("Click Execute to check ESP32 status", includeTimestamp = false)
    }

    private fun manageESP32() {
        clearOutput()

        logMessage("=== ESP32 WiFi MANAGER ===", includeTimestamp = false)
        logMessage("")

        lifecycleScope.launch {
            logMessage("Checking Flipper Zero connection...")

            val flipperConnected = checkFlipperConnection()
            if (!flipperConnected) {
                logMessage("")
                logMessage("⚠️ Flipper Zero not connected!", includeTimestamp = false)
                logMessage("")
                logMessage("Connect Flipper via USB to manage ESP32", includeTimestamp = false)
                return@launch
            }

            logMessage("✓ Flipper connected", includeTimestamp = false)
            logMessage("")
            logMessage("Checking ESP32 Marauder status...")

            val response = sendFlipperCommand("gpio esp status")
            logMessage(response)

            logMessage("")
            logMessage("=== ESP32 MARAUDER COMMANDS ===", includeTimestamp = false)
            logMessage("")

            logMessage("--- WiFi Scanning ---", includeTimestamp = false)
            logMessage("Scan networks:", includeTimestamp = false)
            logMessage("  gpio esp marauder scanap", includeTimestamp = false)
            logMessage("")
            logMessage("Scan stations:", includeTimestamp = false)
            logMessage("  gpio esp marauder scansta", includeTimestamp = false)
            logMessage("")

            logMessage("--- WiFi Attacks ---", includeTimestamp = false)
            logMessage("Deauth attack:", includeTimestamp = false)
            logMessage("  gpio esp marauder attack deauth", includeTimestamp = false)
            logMessage("")
            logMessage("Beacon spam:", includeTimestamp = false)
            logMessage("  gpio esp marauder attack beacon", includeTimestamp = false)
            logMessage("")
            logMessage("Probe spam:", includeTimestamp = false)
            logMessage("  gpio esp marauder attack probe", includeTimestamp = false)
            logMessage("")

            logMessage("--- BLE Attacks ---", includeTimestamp = false)
            logMessage("BLE spam:", includeTimestamp = false)
            logMessage("  gpio esp marauder blespam", includeTimestamp = false)
            logMessage("")

            logMessage("--- Utilities ---", includeTimestamp = false)
            logMessage("List saved files:", includeTimestamp = false)
            logMessage("  gpio esp marauder ls /sd", includeTimestamp = false)
            logMessage("")
            logMessage("Clear screen:", includeTimestamp = false)
            logMessage("  gpio esp marauder clearscreen", includeTimestamp = false)
            logMessage("")

            logMessage("--- Resources ---", includeTimestamp = false)
            logMessage("ESP32 Marauder Wiki:", includeTimestamp = false)
            logMessage("https://github.com/justcallmekoko/ESP32Marauder/wiki", includeTimestamp = false)
        }
    }
}
