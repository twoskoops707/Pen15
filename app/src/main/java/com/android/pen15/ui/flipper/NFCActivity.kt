package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class NFCActivity : BaseToolActivity() {

    override fun getToolName() = "NFC"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing NFC...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("nfc read")
            appendOutput(response)
        }
    }
}
