package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class RFIDActivity : BaseToolActivity() {

    override fun getToolName() = "RFID"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing RFID...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("rfid read")
            appendOutput(response)
        }
    }
}
