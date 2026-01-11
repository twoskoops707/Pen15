package com.android.pen15.ui.utilities

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class ESP32ManagerActivity : BaseToolActivity() {

    override fun getToolName() = "ESP32Manager"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing ESP32Manager...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("esp32 status")
            appendOutput(response)
        }
    }
}
