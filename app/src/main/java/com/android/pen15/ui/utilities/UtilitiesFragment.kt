package com.android.pen15.ui.utilities

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

class UtilitiesFragment : Fragment() {
    
    private val utilities = listOf(
        Tool("qr_scanner", "QR Scanner", "Scan QR codes and barcodes", ToolCategory.UTILITIES),
        Tool("script_builder", "Script Builder", "Create custom pentesting scripts", ToolCategory.UTILITIES),
        Tool("cheat_sheet", "Cheat Sheet", "Quick command reference", ToolCategory.UTILITIES),
        Tool("esp32", "ESP32 Manager", "Control AWOK Marauder device", ToolCategory.UTILITIES),
        Tool("settings", "Settings", "App configuration", ToolCategory.UTILITIES)
    )
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_utilities, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val toolsContainer = view.findViewById<LinearLayout>(R.id.toolsContainer)
        
        utilities.forEach { tool ->
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
            "qr_scanner" -> "QRScannerActivity"
            "script_builder" -> "ScriptBuilderActivity"
            "cheat_sheet" -> "CheatSheetActivity"
            "esp32" -> "ESP32ManagerActivity"
            "settings" -> "SettingsActivity"
            else -> return
        }

        try {
            val activityClass = Class.forName("com.android.pen15.ui.utilities.$className")
            startActivity(Intent(requireContext(), activityClass))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
