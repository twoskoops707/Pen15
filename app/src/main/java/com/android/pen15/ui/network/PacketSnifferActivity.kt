package com.android.pen15.ui.network

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.android.pen15.utils.ToolChecker
import kotlinx.coroutines.launch

class PacketSnifferActivity : BaseToolActivity() {

    private var toolAvailable = false

    override fun getToolName() = "Packet Sniffer"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() { startSniff() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logMessage("Packet Sniffer (tcpdump)", includeTimestamp = false)
        logMessage("Capture and analyze network traffic", includeTimestamp = false)
        logMessage("")

        // Check tool availability
        lifecycleScope.launch {
            toolAvailable = ToolChecker.checkToolAvailable(this@PacketSnifferActivity, "tcpdump")
            if (!toolAvailable) {
                logMessage("⚠️ tcpdump not installed!", includeTimestamp = false)
                logMessage("", includeTimestamp = false)
                logMessage("Install with:", includeTimestamp = false)
                logMessage("${ToolChecker.getInstallCommand("tcpdump")}", includeTimestamp = false)
                executeButton.isEnabled = false
            } else {
                logMessage("✓ tcpdump is available", includeTimestamp = false)
                logMessage("")
                logMessage("Will capture 100 packets on wlan0", includeTimestamp = false)
                logMessage("Output saved to: capture.pcap", includeTimestamp = false)
            }
        }
    }

    private fun startSniff() {
        if (!toolAvailable) {
            logMessage("ERROR: tcpdump not available")
            return
        }

        clearOutput()
        logMessage("Starting packet capture...")
        logMessage("Interface: wlan0")
        logMessage("Packet limit: 100")
        logMessage("Output: ~/capture.pcap")
        logMessage("")

        executeCommandWithProgress("tcpdump -i wlan0 -c 100 -w ~/capture.pcap -v", useTermux = true)

        logMessage("")
        logMessage("✓ Capture complete!")
        logMessage("File saved to: ~/capture.pcap")
        logMessage("")
        logMessage("Analyze with: tcpdump -r ~/capture.pcap")
    }
}
