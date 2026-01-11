package com.android.pen15.ui.crypto

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class CreditCardReaderActivity : BaseToolActivity() {

    override fun getToolName() = "CreditCardReader"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing CreditCardReader...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("nfc-poll")
            appendOutput(response)
        }
    }
}
