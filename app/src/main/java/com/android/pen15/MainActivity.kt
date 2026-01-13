package com.android.pen15

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            setupButtons()
            Log.d(TAG, "MainActivity initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MainActivity", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupButtons() {
        // Flipper Tools
        findViewById<Button>(R.id.btnRFID)?.setOnClickListener {
            launchActivity("RFID", com.android.pen15.ui.flipper.RFIDActivity::class.java)
        }

        findViewById<Button>(R.id.btnNFC)?.setOnClickListener {
            launchActivity("NFC", com.android.pen15.ui.flipper.NFCActivity::class.java)
        }

        findViewById<Button>(R.id.btnSubGHz)?.setOnClickListener {
            launchActivity("SubGHz", com.android.pen15.ui.flipper.SubGHzActivity::class.java)
        }

        findViewById<Button>(R.id.btnInfrared)?.setOnClickListener {
            launchActivity("Infrared", com.android.pen15.ui.flipper.InfraredActivity::class.java)
        }

        findViewById<Button>(R.id.btnIButton)?.setOnClickListener {
            launchActivity("IButton", com.android.pen15.ui.flipper.IButtonActivity::class.java)
        }

        findViewById<Button>(R.id.btnGPIO)?.setOnClickListener {
            launchActivity("GPIO", com.android.pen15.ui.flipper.GPIOActivity::class.java)
        }

        findViewById<Button>(R.id.btnBadUSB)?.setOnClickListener {
            launchActivity("BadUSB", com.android.pen15.ui.flipper.BadUSBActivity::class.java)
        }

        // ESP32
        findViewById<Button>(R.id.btnESP32)?.setOnClickListener {
            launchActivity("ESP32", com.android.pen15.ui.utilities.ESP32ManagerActivity::class.java)
        }

        // Settings
        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            launchActivity("Settings", com.android.pen15.ui.utilities.SettingsActivity::class.java)
        }
    }

    private fun launchActivity(name: String, activityClass: Class<*>) {
        try {
            Log.d(TAG, "Attempting to launch $name activity")
            Toast.makeText(this, "Opening $name...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, activityClass)
            startActivity(intent)
            Log.d(TAG, "Successfully launched $name activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $name activity", e)
            Toast.makeText(this, "Failed to open $name: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
