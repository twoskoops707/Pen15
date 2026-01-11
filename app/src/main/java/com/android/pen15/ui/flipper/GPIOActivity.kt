package com.android.pen15.ui.flipper

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class GPIOActivity : BaseToolActivity() {

    override fun getToolName() = "GPIO"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing GPIO...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("gpio status")
            appendOutput(response)
        }
    }
}
