package com.android.pen15.ui.utilities

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class ScriptBuilderActivity : BaseToolActivity() {

    override fun getToolName() = "ScriptBuilder"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing ScriptBuilder...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("edit script")
            appendOutput(response)
        }
    }
}
