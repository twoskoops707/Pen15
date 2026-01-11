package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class ARPPoisonerActivity : BaseToolActivity() {

    override fun getToolName() = "ARPPoisoner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing ARPPoisoner...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("arpspoof")
            appendOutput(response)
        }
    }
}
