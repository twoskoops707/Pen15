package com.android.pen15.ui.network

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.android.pen15.R
import com.android.pen15.domain.Tool
import com.android.pen15.domain.ToolCategory
import com.android.pen15.ui.components.ToolCardView

class NetworkToolsFragment : Fragment() {
    
    private val networkTools = listOf(
        Tool("wifi_deauth", "WiFi Deauth", "Disconnect clients from WiFi networks", ToolCategory.NETWORK),
        Tool("wifi_capture", "WiFi Capture", "Capture WPA/WPA2 handshakes", ToolCategory.NETWORK),
        Tool("nmap", "Network Scanner", "Scan networks with Nmap", ToolCategory.NETWORK),
        Tool("packet_sniffer", "Packet Sniffer", "Capture and analyze network traffic", ToolCategory.NETWORK),
        Tool("bluetooth", "Bluetooth Scanner", "Scan for BLE devices", ToolCategory.NETWORK),
        Tool("arp_poison", "ARP Poisoner", "Man-in-the-middle attacks", ToolCategory.NETWORK)
    )
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_network_tools, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val toolsContainer = view.findViewById<LinearLayout>(R.id.toolsContainer)
        
        networkTools.forEach { tool ->
            val toolCard = ToolCardView(requireContext())
            toolCard.setTool(tool)
            toolCard.setOnClickListener {
                launchTool(tool.id)
            }
            toolsContainer.addView(toolCard)
        }
    }

    private fun launchTool(toolId: String) {
        val className = when (toolId) {
            "wifi_deauth" -> "WiFiDeauthActivity"
            "wifi_capture" -> "WiFiCaptureActivity"
            "nmap" -> "NetworkScannerActivity"
            "packet_sniffer" -> "PacketSnifferActivity"
            "bluetooth" -> "BluetoothScannerActivity"
            "arp_poison" -> "ARPPoisonerActivity"
            else -> return
        }

        try {
            val activityClass = Class.forName("com.android.pen15.ui.network.$className")
            startActivity(Intent(requireContext(), activityClass))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
