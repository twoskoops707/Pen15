package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class GPIOActivity : BaseToolActivity() {

    override fun getToolName() = "GPIO"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("GPIO Control")
        appendOutput("")
        appendOutput("Note: GPIO pins are controlled via the")
        appendOutput("Flipper GUI app, not CLI commands")
        appendOutput("")
        appendOutput("Common GPIO commands:")
        appendOutput("• power 5v 1 - Enable 5V on pin 1")
        appendOutput("• power 5v 0 - Disable 5V on pin 1")
        appendOutput("")
        appendOutput("Testing 5V status...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("power 5v 0")
            appendOutput("Response: $response")
        }
    }
}
