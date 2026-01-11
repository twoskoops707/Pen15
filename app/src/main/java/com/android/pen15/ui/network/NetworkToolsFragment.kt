package com.android.pen15.ui.network

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
                android.widget.Toast.makeText(requireContext(), "Opening ${tool.name}", android.widget.Toast.LENGTH_SHORT).show()
            }
            toolsContainer.addView(toolCard)
        }
    }
}
