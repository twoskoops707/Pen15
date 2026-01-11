package com.android.pen15.ui.utilities

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class CheatSheetActivity : BaseToolActivity() {

    override fun getToolName() = "CheatSheet"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing CheatSheet...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("show cheat sheet")
            appendOutput(response)
        }
    }
}
