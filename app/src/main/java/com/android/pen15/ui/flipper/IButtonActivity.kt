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
        lifecycleScope.launch {
            val response = sendFlipperCommand("ibutton read")
            appendOutput(response)
        }
    }
}
