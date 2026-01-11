package com.android.pen15.ui.flipper

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

class FlipperToolsFragment : Fragment() {
    
    private val flipperTools = listOf(
        Tool("subghz", "Sub-GHz", "Scan & capture radio frequencies (315, 433, 868, 915 MHz)", ToolCategory.FLIPPER, true),
        Tool("rfid", "RFID", "Read, write & emulate RFID cards (125kHz)", ToolCategory.FLIPPER, true),
        Tool("nfc", "NFC", "Read & emulate NFC cards and tags", ToolCategory.FLIPPER, true),
        Tool("infrared", "Infrared", "Capture & replay IR remote signals", ToolCategory.FLIPPER, true),
        Tool("gpio", "GPIO", "Control GPIO pins and interfaces", ToolCategory.FLIPPER, true),
        Tool("badusb", "BadUSB", "Keyboard injection & USB attacks", ToolCategory.FLIPPER, true),
        Tool("ibutton", "iButton", "Read & emulate 1-Wire iButton devices", ToolCategory.FLIPPER, true)
    )
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_flipper_tools, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val toolsContainer = view.findViewById<LinearLayout>(R.id.toolsContainer)
        
        flipperTools.forEach { tool ->
            val toolCard = ToolCardView(requireContext())
            toolCard.setTool(tool)
            toolCard.setOnClickListener {
                launchTool(tool.id)
            }
            toolsContainer.addView(toolCard)
        }
    }

    private fun launchTool(toolId: String) {
        val intent = when (toolId) {
            "subghz" -> android.content.Intent(requireContext(), SubGHzActivity::class.java)
            "rfid" -> android.content.Intent(requireContext(), RFIDActivity::class.java)
            "nfc" -> android.content.Intent(requireContext(), NFCActivity::class.java)
            "infrared" -> android.content.Intent(requireContext(), InfraredActivity::class.java)
            "gpio" -> android.content.Intent(requireContext(), GPIOActivity::class.java)
            "badusb" -> android.content.Intent(requireContext(), BadUSBActivity::class.java)
            "ibutton" -> android.content.Intent(requireContext(), IButtonActivity::class.java)
            else -> return
        }
        startActivity(intent)
    }
}
