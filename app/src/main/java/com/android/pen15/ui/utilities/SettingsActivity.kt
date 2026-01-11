package com.android.pen15.ui.utilities

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class SettingsActivity : BaseToolActivity() {

    override fun getToolName() = "Settings"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing Settings...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("settings")
            appendOutput(response)
        }
    }
}
