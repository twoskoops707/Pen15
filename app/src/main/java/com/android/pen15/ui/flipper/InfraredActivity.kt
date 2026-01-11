package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class InfraredActivity : BaseToolActivity() {

    override fun getToolName() = "Infrared"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing Infrared...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("infrared capture")
            appendOutput(response)
        }
    }
}
