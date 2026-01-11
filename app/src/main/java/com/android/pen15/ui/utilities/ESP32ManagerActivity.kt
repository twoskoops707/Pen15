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
            logMessage("⚠️ IMPORTANT: ESP32 Marauder must be connected", includeTimestamp = false)
            logMessage("to Flipper Zero GPIO pins (UART)", includeTimestamp = false)
            logMessage("")

            logMessage("--- WiFi Scanning ---", includeTimestamp = false)
            logMessage("Scan access points:", includeTimestamp = false)
            logMessage("  scanap", includeTimestamp = false)
            logMessage("")
            logMessage("List scanned APs:", includeTimestamp = false)
            logMessage("  list -a", includeTimestamp = false)
            logMessage("")
            logMessage("Stop scan:", includeTimestamp = false)
            logMessage("  stopscan", includeTimestamp = false)
            logMessage("")

            logMessage("--- Target Selection ---", includeTimestamp = false)
            logMessage("Select AP by ID:", includeTimestamp = false)
            logMessage("  select -a 0", includeTimestamp = false)
            logMessage("")
            logMessage("Clear selection:", includeTimestamp = false)
            logMessage("  clearap", includeTimestamp = false)
            logMessage("")

            logMessage("--- WiFi Attacks ---", includeTimestamp = false)
            logMessage("Deauth flood:", includeTimestamp = false)
            logMessage("  attack -t deauth", includeTimestamp = false)
            logMessage("")
            logMessage("Beacon spam:", includeTimestamp = false)
            logMessage("  attack -t beacon", includeTimestamp = false)
            logMessage("")
            logMessage("Probe request spam:", includeTimestamp = false)
            logMessage("  attack -t probe", includeTimestamp = false)
            logMessage("")

            logMessage("--- BLE Attacks ---", includeTimestamp = false)
            logMessage("BLE spam all:", includeTimestamp = false)
            logMessage("  btspamall", includeTimestamp = false)
            logMessage("")
            logMessage("Sniff Bluetooth:", includeTimestamp = false)
            logMessage("  sniffbt", includeTimestamp = false)
            logMessage("")

            logMessage("--- Resources ---", includeTimestamp = false)
            logMessage("ESP32 Marauder Wiki:", includeTimestamp = false)
            logMessage("https://github.com/justcallmekoko/ESP32Marauder/wiki", includeTimestamp = false)
        }
    }
}
