package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class WiFiDeauthActivity : BaseToolActivity() {

    override fun getToolName() = "WiFiDeauth"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing WiFiDeauth...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("wlan0 deauth")
            appendOutput(response)
        }
    }
}
