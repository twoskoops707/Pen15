package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class BadUSBActivity : BaseToolActivity() {

    override fun getToolName() = "BadUSB"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("BadUSB Script Manager")
        appendOutput("")
        appendOutput("Note: BadUSB scripts are executed via")
        appendOutput("Flipper GUI, not CLI commands")
        appendOutput("")
        appendOutput("Listing available BadUSB scripts...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("storage list /ext/badusb")
            appendOutput("")
            appendOutput("Scripts in /ext/badusb:")
            appendOutput(response)
            appendOutput("")
            appendOutput("To run: Use Flipper GUI")
            appendOutput("Main Menu → Bad USB → Select script")
        }
    }
}
