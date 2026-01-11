package com.android.pen15.ui.utilities

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

class UtilitiesFragment : Fragment() {
    
    private val utilities = listOf(
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
                android.widget.Toast.makeText(requireContext(), "Opening ${tool.name}", android.widget.Toast.LENGTH_SHORT).show()
            }
            toolsContainer.addView(toolCard)
        }
    }
}
