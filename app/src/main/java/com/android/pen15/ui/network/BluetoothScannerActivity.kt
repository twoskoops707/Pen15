package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class BluetoothScannerActivity : BaseToolActivity() {

    override fun getToolName() = "BluetoothScanner"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing BluetoothScanner...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("hcitool scan")
            appendOutput(response)
        }
    }
}
