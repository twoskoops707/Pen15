package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class InfraredActivity : BaseToolActivity() {

    override fun getToolName() = "Infrared"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Starting infrared capture...")
        appendOutput("Point remote at Flipper and press button")
        lifecycleScope.launch {
            val response = sendFlipperCommand("ir rx")
            appendOutput(response)
        }
    }
}
