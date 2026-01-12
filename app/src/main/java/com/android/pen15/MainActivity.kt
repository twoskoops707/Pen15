package com.android.pen15

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            setupButtons()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        // Flipper Tools
        findViewById<Button>(R.id.btnRFID)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.RFIDActivity::class.java))
        }

        findViewById<Button>(R.id.btnNFC)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.NFCActivity::class.java))
        }

        findViewById<Button>(R.id.btnSubGHz)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.SubGHzActivity::class.java))
        }

        findViewById<Button>(R.id.btnInfrared)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.InfraredActivity::class.java))
        }

        findViewById<Button>(R.id.btnIButton)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.IButtonActivity::class.java))
        }

        findViewById<Button>(R.id.btnGPIO)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.GPIOActivity::class.java))
        }

        findViewById<Button>(R.id.btnBadUSB)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.flipper.BadUSBActivity::class.java))
        }

        // ESP32
        findViewById<Button>(R.id.btnESP32)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.utilities.ESP32ManagerActivity::class.java))
        }

        // Settings
        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, com.android.pen15.ui.utilities.SettingsActivity::class.java))
        }
    }
}
