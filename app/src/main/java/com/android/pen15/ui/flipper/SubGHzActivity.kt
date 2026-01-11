package com.android.pen15.ui.flipper

import android.os.Bundle
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.ui.base.BaseToolActivity
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class SubGHzActivity : BaseToolActivity() {

    private lateinit var btnStartScan: Button
    private lateinit var btnStopScan: Button
    private lateinit var frequencyChips: ChipGroup
    private var scanning = false

    override fun getToolName() = "Sub-GHz Scanner"
    override fun getLayoutResource() = R.layout.activity_subghz
    override fun onToolExecute() { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        btnStartScan = findViewById(R.id.btnStartScan)
        btnStopScan = findViewById(R.id.btnStopScan)
        frequencyChips = findViewById(R.id.frequencyChips)

        btnStartScan.setOnClickListener { startScan() }
        btnStopScan.setOnClickListener { stopScan() }
    }

    private fun startScan() {
        val frequency = when (frequencyChips.checkedChipId) {
            R.id.chip315 -> 315000000
            R.id.chip390 -> 390000000
            R.id.chip433 -> 433920000
            R.id.chip868 -> 868000000
            R.id.chip915 -> 915000000
            else -> 433920000
        }

        scanning = true
        btnStartScan.isEnabled = false
        btnStopScan.isEnabled = true

        appendOutput("Starting Sub-GHz scan at ${frequency / 1000000} MHz...")

        lifecycleScope.launch {
            val response = sendFlipperCommand("subghz rx $frequency")
            appendOutput(response)
        }
    }

    private fun stopScan() {
        scanning = false
        btnStartScan.isEnabled = true
        btnStopScan.isEnabled = false

        appendOutput("Stopping scan...")

        lifecycleScope.launch {
            val response = sendFlipperCommand("subghz stop")
            appendOutput(response)
        }
    }
}
