package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class NetworkScannerActivity : BaseToolActivity() {

    override fun getToolName() = "NetworkScanner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing NetworkScanner...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("nmap scan")
            appendOutput(response)
        }
    }
}
