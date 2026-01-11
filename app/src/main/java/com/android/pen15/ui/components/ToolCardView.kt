package com.android.pen15.ui.components

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.pen15.R
import com.android.pen15.core.ConnectionManager
import com.android.pen15.domain.Tool

class ToolCardView(context: Context) : ConstraintLayout(context) {
    
    private val toolIcon: ImageView
    private val toolTitle: TextView
    private val toolDescription: TextView
    private val toolStatus: TextView
    
    init {
        LayoutInflater.from(context).inflate(R.layout.item_tool_card, this, true)
        
        toolIcon = findViewById(R.id.toolIcon)
        toolTitle = findViewById(R.id.toolTitle)
        toolDescription = findViewById(R.id.toolDescription)
        toolStatus = findViewById(R.id.toolStatus)
    }
    
    fun setTool(tool: Tool) {
        toolTitle.text = tool.name
        toolDescription.text = tool.description
        
        // Check if tool requires connection
        val connectionManager = ConnectionManager.getInstance()
        val status = when {
            tool.requiresFlipperConnection && !connectionManager.isConnected() -> {
                toolStatus.setTextColor(context.getColor(R.color.glass_warning))
                "Requires Connection"
            }
            !tool.isAvailable -> {
                toolStatus.setTextColor(context.getColor(R.color.glass_error))
                "Install Required"
            }
            else -> {
                toolStatus.setTextColor(context.getColor(R.color.glass_success))
                "Ready"
            }
        }
        toolStatus.text = status
        
        // Set appropriate icon based on tool ID
        val iconRes = when (tool.id) {
            "subghz" -> android.R.drawable.ic_menu_compass
            "rfid", "nfc" -> android.R.drawable.ic_menu_view
            "infrared" -> android.R.drawable.ic_menu_send
            "gpio" -> android.R.drawable.ic_menu_manage
            "badusb" -> android.R.drawable.ic_menu_upload
            "ibutton" -> android.R.drawable.ic_menu_info_details
            "wifi_deauth", "wifi_capture" -> android.R.drawable.ic_menu_search
            "nmap", "packet_sniffer" -> android.R.drawable.ic_menu_mapmode
            "bluetooth" -> android.R.drawable.ic_menu_mylocation
            "hashcat", "aircrack" -> android.R.drawable.ic_lock_lock
            else -> android.R.drawable.ic_menu_preferences
        }
        toolIcon.setImageResource(iconRes)
    }
}
