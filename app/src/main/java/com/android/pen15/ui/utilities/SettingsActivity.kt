package com.android.pen15.ui.utilities

import android.os.Bundle
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class SettingsActivity : BaseToolActivity() {

    override fun getToolName() = "App Settings & Info"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { showSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("App Settings & System Info", includeTimestamp = false)
        logMessage("Configuration and diagnostics", includeTimestamp = false)
        logMessage("")
        logMessage("Click Execute to view settings", includeTimestamp = false)
    }

    private fun showSettings() {
        clearOutput()

        logMessage("=== PEN15 SETTINGS & INFO ===", includeTimestamp = false)
        logMessage("")

        logMessage("--- App Information ---", includeTimestamp = false)
        logMessage("App Version: 2.1.0", includeTimestamp = false)
        logMessage("Build: 21", includeTimestamp = false)
        logMessage("Package: com.android.pen15", includeTimestamp = false)
        logMessage("")

        logMessage("--- Device Information ---", includeTimestamp = false)
        logMessage("Model: ${Build.MODEL}", includeTimestamp = false)
        logMessage("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", includeTimestamp = false)
        logMessage("Manufacturer: ${Build.MANUFACTURER}", includeTimestamp = false)
        logMessage("")

        logMessage("--- System Status ---", includeTimestamp = false)

        lifecycleScope.launch {
            // Check Flipper connection
            val flipperConnected = checkFlipperConnection()
            logMessage("Flipper Zero: ${if (flipperConnected) "✓ Connected" else "⚠️ Not Connected"}", includeTimestamp = false)

            // Check Termux
            val termuxInstalled = try {
                packageManager.getPackageInfo("com.termux", 0)
                true
            } catch (e: Exception) {
                false
            }
            logMessage("Termux: ${if (termuxInstalled) "✓ Installed" else "⚠️ Not Installed"}", includeTimestamp = false)

            logMessage("")
            logMessage("--- Tool Availability ---", includeTimestamp = false)

            val tools = listOf(
                "nmap" to "Network Scanner",
                "tcpdump" to "Packet Sniffer",
                "aircrack-ng" to "WiFi Tools",
                "hcitool" to "Bluetooth Scanner",
                "arpspoof" to "ARP Poisoner",
                "hashcat" to "Hash Cracker"
            )

            tools.forEach { (tool, name) ->
                val available = ToolChecker.checkToolAvailable(this@SettingsActivity, tool)
                logMessage("$name: ${if (available) "✓" else "✗"}", includeTimestamp = false)
            }

            logMessage("")
            logMessage("--- Installation Commands ---", includeTimestamp = false)
            logMessage("If tools are missing, install with:", includeTimestamp = false)
            logMessage("")
            logMessage("pkg update && pkg upgrade", includeTimestamp = false)
            logMessage("pkg install nmap tcpdump aircrack-ng", includeTimestamp = false)
            logMessage("pkg install bluez-utils dsniff hashcat", includeTimestamp = false)
            logMessage("")

            logMessage("--- Flipper Zero Connection ---", includeTimestamp = false)
            if (!flipperConnected) {
                logMessage("To connect Flipper Zero:", includeTimestamp = false)
                logMessage("1. Connect Flipper via USB", includeTimestamp = false)
                logMessage("2. Accept USB permission dialog", includeTimestamp = false)
                logMessage("3. Restart app", includeTimestamp = false)
            } else {
                logMessage("Status: Connected via /dev/ttyACM0", includeTimestamp = false)
                logMessage("Baud rate: 115200", includeTimestamp = false)
            }
            logMessage("")

            logMessage("--- Termux Configuration ---", includeTimestamp = false)
            if (!termuxInstalled) {
                logMessage("⚠️ Termux not installed!", includeTimestamp = false)
                logMessage("")
                logMessage("Install Termux from:", includeTimestamp = false)
                logMessage("https://f-droid.org/packages/com.termux/", includeTimestamp = false)
                logMessage("")
                logMessage("⚠️ Do NOT install from Google Play", includeTimestamp = false)
                logMessage("   (outdated version)", includeTimestamp = false)
            } else {
                logMessage("✓ Termux installed", includeTimestamp = false)
                logMessage("")
                logMessage("Grant Termux permissions:", includeTimestamp = false)
                logMessage("termux-setup-storage", includeTimestamp = false)
            }
            logMessage("")

            logMessage("--- Support & Resources ---", includeTimestamp = false)
            logMessage("GitHub:", includeTimestamp = false)
            logMessage("https://github.com/yourusername/Pen15", includeTimestamp = false)
            logMessage("")
            logMessage("Flipper Zero Docs:", includeTimestamp = false)
            logMessage("https://docs.flipperzero.one/", includeTimestamp = false)
            logMessage("")
            logMessage("Termux Wiki:", includeTimestamp = false)
            logMessage("https://wiki.termux.com/", includeTimestamp = false)
        }
    }
}
