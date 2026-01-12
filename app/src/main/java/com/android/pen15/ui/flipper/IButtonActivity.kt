package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class IButtonActivity : BaseToolActivity() {

    override fun getToolName() = "IButton"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("iButton Reader")
        appendOutput("")
        appendOutput("Starting iButton read mode...")
        appendOutput("Touch iButton device to Flipper's contact pad")
        appendOutput("")
        appendOutput("Supported types:")
        appendOutput("• Dallas (DS1990, DS1992, DS1996, DS1971)")
        appendOutput("• Cyfral")
        appendOutput("• Metakom")
        appendOutput("")
        lifecycleScope.launch {
            showProgress(true)
            // Correct command is "ikey read" not "ibutton read"
            val response = sendFlipperCommand("ikey read")
            appendOutput("")
            if (response.contains("Error")) {
                appendOutput("✗ Failed to read iButton")
                appendOutput(response)
            } else {
                appendOutput("Response:")
                appendOutput(response)
            }
            showProgress(false)
        }
    }
}
