package com.android.pen15.ui.network

import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import kotlinx.coroutines.launch

class PacketSnifferActivity : BaseToolActivity() {

    override fun getToolName() = "PacketSniffer"
    override fun getLayoutResource() = R.layout.activity_generic_tool
    override fun onToolExecute() {
        appendOutput("Executing PacketSniffer...")
        lifecycleScope.launch {
            val response = sendFlipperCommand("tcpdump")
            appendOutput(response)
        }
    }
}
