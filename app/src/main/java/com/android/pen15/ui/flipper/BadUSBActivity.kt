package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class BadUSBActivity : BaseToolActivity() {

    override fun getToolName() = "BadUSB"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing BadUSB...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("badusb execute")
            appendOutput(response)
        }
    }
}
