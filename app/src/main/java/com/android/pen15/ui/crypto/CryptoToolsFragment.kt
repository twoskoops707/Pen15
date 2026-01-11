package com.android.pen15.ui.crypto

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

class CryptoToolsFragment : Fragment() {
    
    private val cryptoTools = listOf(
        Tool("hashcat", "Hash Cracker", "Crack password hashes with Hashcat", ToolCategory.CRYPTO),
        Tool("aircrack", "Aircrack-ng", "WiFi security auditing suite", ToolCategory.CRYPTO),
        Tool("payload_gen", "Payload Generator", "Generate attack payloads", ToolCategory.CRYPTO),
        Tool("exploits", "Exploit Database", "Browse & search exploits", ToolCategory.CRYPTO),
        Tool("credit_card", "Card Reader", "Read NFC payment cards", ToolCategory.CRYPTO)
    )
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_crypto_tools, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val toolsContainer = view.findViewById<LinearLayout>(R.id.toolsContainer)
        
        cryptoTools.forEach { tool ->
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
            "hashcat" -> "HashCrackerActivity"
            "aircrack" -> "AircrackActivity"
            "payload_gen" -> "PayloadGeneratorActivity"
            "exploits" -> "ExploitDatabaseActivity"
            "credit_card" -> "CreditCardReaderActivity"
            else -> return
        }

        try {
            val activityClass = Class.forName("com.android.pen15.ui.crypto.$className")
            startActivity(Intent(requireContext(), activityClass))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
