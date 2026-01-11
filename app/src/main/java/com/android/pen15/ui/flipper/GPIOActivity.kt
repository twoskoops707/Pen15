package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class GPIOActivity : BaseToolActivity() {

    override fun getToolName() = "GPIO"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("GPIO & ESP32 Marauder Control")
        appendOutput("")
        appendOutput("=== GPIO Power Control ===")
        appendOutput("Enable 5V on pin 1:")
        appendOutput("  power 5v 1")
        appendOutput("")
        appendOutput("Disable 5V:")
        appendOutput("  power 5v 0")
        appendOutput("")
        appendOutput("=== ESP32 Marauder (via GPIO) ===")
        appendOutput("")
        appendOutput("AWOK Mini V3 / Marauder Board:")
        appendOutput("1. Connect to Flipper GPIO pins (UART)")
        appendOutput("2. Use ESP32 Manager for Marauder commands")
        appendOutput("")
        appendOutput("Quick Start:")
        appendOutput("• scanap - Scan WiFi access points")
        appendOutput("• list -a - List found APs")
        appendOutput("• select -a 0 - Select first AP")
        appendOutput("• attack -t deauth - Deauth attack")
        appendOutput("")
        appendOutput("Testing Flipper connection...")
        lifecycleScope.launch {
            showProgress(true)
            val response = sendFlipperCommand("device_info")
            if (response.contains("Error")) {
                appendOutput("✗ Flipper not connected")
            } else {
                appendOutput("✓ Flipper connected via USB")
                appendOutput("")
                appendOutput("Testing 5V power control...")
                val powerResponse = sendFlipperCommand("power 5v 0")
                appendOutput("Power command: $powerResponse")
            }
            showProgress(false)
        }
    }
}
