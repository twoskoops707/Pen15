package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class RFIDActivity : BaseToolActivity() {

    override fun getToolName() = "RFID"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("RFID Reader")
        appendOutput("")
        appendOutput("Starting RFID read mode...")
        appendOutput("Hold RFID card near Flipper's antenna")
        appendOutput("")
        appendOutput("Supported formats:")
        appendOutput("• EM4100, EM4102 (125 kHz)")
        appendOutput("• HID Prox")
        appendOutput("• Indala")
        appendOutput("")
        lifecycleScope.launch {
            showProgress(true)
            val response = sendFlipperCommand("rfid read")
            appendOutput("Response:")
            appendOutput(response)
            appendOutput("")
            if (!response.contains("Error")) {
                appendOutput("✓ RFID read command sent")
                appendOutput("Wave card near Flipper now...")
            }
            showProgress(false)
        }
    }
}
