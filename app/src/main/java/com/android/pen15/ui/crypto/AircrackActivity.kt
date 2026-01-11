package com.android.pen15.ui.crypto

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class AircrackActivity : BaseToolActivity() {

    override fun getToolName() = "Aircrack"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing Aircrack...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("aircrack-ng")
            appendOutput(response)
        }
    }
}
