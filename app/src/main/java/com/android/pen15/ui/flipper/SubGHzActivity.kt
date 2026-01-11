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

        logMessage("Sub-GHz scanner ready")
        logMessage("Select frequency and tap 'Start Scan'")
    }

    private fun startScan() {
        val frequency = when (frequencyChips.checkedChipId) {
            R.id.chip315 -> 315000000L
            R.id.chip390 -> 390000000L
            R.id.chip433 -> 433920000L
            R.id.chip868 -> 868000000L
            R.id.chip915 -> 915000000L
            else -> 433920000L
        }

        scanning = true
        btnStartScan.isEnabled = false
        btnStopScan.isEnabled = true

        clearOutput()
        logMessage("Configuring Sub-GHz radio...")
        logMessage("Frequency: ${frequency / 1000000} MHz")
        logMessage("Mode: Receive (RX)")

        lifecycleScope.launch {
            showProgress(true)

            // Send Flipper command
            val response = sendFlipperCommand("subghz rx $frequency")
            logMessage("Flipper response: $response")

            if (response.contains("OK")) {
                logMessage("✓ Scanning started successfully")
                logMessage("Listening for signals...")
                logMessage("Common uses: car keys, garage doors, remotes")
            } else {
                logMessage("✗ Error starting scan")
                stopScan()
            }

            showProgress(false)
        }
    }

    private fun stopScan() {
        scanning = false
        btnStartScan.isEnabled = true
        btnStopScan.isEnabled = false

        logMessage("Stopping scan...")

        lifecycleScope.launch {
            showProgress(true)
            val response = sendFlipperCommand("subghz stop")
            logMessage("Scan stopped")
            showProgress(false)
        }
    }
}
