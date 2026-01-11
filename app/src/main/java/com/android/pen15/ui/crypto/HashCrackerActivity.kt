package com.android.pen15.ui.crypto

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class HashCrackerActivity : BaseToolActivity() {

    override fun getToolName() = "HashCracker"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing HashCracker...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("hashcat")
            appendOutput(response)
        }
    }
}
