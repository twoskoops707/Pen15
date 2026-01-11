package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class WiFiCaptureActivity : BaseToolActivity() {

    override fun getToolName() = "WiFiCapture"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing WiFiCapture...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("airodump-ng")
            appendOutput(response)
        }
    }
}
