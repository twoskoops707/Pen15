package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class NFCActivity : BaseToolActivity() {

    override fun getToolName() = "NFC"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("NFC Reader")
        appendOutput("")
        appendOutput("⚠️ NOTE: NFC CLI commands were removed")
        appendOutput("in recent Flipper firmware.")
        appendOutput("")
        appendOutput("To use NFC features:")
        appendOutput("1. Open Flipper GUI")
        appendOutput("2. Go to Main Menu → NFC")
        appendOutput("3. Select 'Read' or 'Saved'")
        appendOutput("")
        appendOutput("Supported cards: MIFARE, EMV, NFC-A/B")
        appendOutput("")
        appendOutput("Checking Flipper connection...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("device_info")
            if (response.contains("Error")) {
                appendOutput("✗ Flipper not connected")
            } else {
                appendOutput("✓ Flipper connected via USB")
                appendOutput("Use Flipper screen to access NFC features")
            }
        }
    }
}
