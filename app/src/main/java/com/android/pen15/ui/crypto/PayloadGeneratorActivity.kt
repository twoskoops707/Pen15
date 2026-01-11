package com.android.pen15.ui.crypto

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class PayloadGeneratorActivity : BaseToolActivity() {

    override fun getToolName() = "PayloadGenerator"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing PayloadGenerator...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("generate payload")
            appendOutput(response)
        }
    }
}
