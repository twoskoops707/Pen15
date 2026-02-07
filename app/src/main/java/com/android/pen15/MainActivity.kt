package com.android.pen15

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.android.pen15.databinding.ActivityMainBinding
import com.android.pen15.model.AppState
import com.android.pen15.serial.FlipperSerial
import com.android.pen15.serial.SerialListener

class MainActivity : AppCompatActivity(), SerialListener {

    private lateinit var binding: ActivityMainBinding
    private val appState: AppState by viewModels()
    var serial: FlipperSerial? = null
        private set
    val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val trace = e.stackTraceToString()
            try {
                val file = java.io.File(getExternalFilesDir(null), "crash.txt")
                file.writeText(trace)
            } catch (_: Exception) {}
            try {
                val file2 = java.io.File(filesDir, "crash.txt")
                file2.writeText(trace)
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Toast.makeText(this, "INFLATE ERROR: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                val file = java.io.File(getExternalFilesDir(null), "crash.txt")
                file.writeText(e.stackTraceToString())
            } catch (_: Exception) {}
            return
        }

        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            binding.bottomNav.setupWithNavController(navHostFragment.navController)
        } catch (e: Exception) {
            Toast.makeText(this, "NAV ERROR: ${e.message}", Toast.LENGTH_LONG).show()
        }

        serial = FlipperSerial(this).also {
            it.listener = this
            it.register()
        }

        appendToTerminal("=== PEN15 v2.0.4 BUILD 227 ===")
        appendToTerminal("Tap STATUS tab to connect your device")

        Toast.makeText(this, "PEN15 v2.0.4 Build 227", Toast.LENGTH_LONG).show()
    }

    fun sendCommand(cmd: String) {
        serial?.sendCommand(cmd)
    }

    fun appendToTerminal(text: String) {
        appState.appendOutput(text + "\n")
    }

    override fun onSerialConnect(deviceName: String) {
        handler.post {
            val type = serial?.deviceType ?: FlipperSerial.DeviceType.NONE
            appState.setConnected(true, type, deviceName)
            appendToTerminal("[Connected] $deviceName")
        }
    }

    override fun onSerialData(data: String) {
        handler.post {
            appState.appendOutput(data)
        }
    }

    override fun onSerialError(error: String) {
        handler.post {
            appendToTerminal("[Error] $error")
        }
    }

    override fun onSerialDisconnect() {
        handler.post {
            appState.setDisconnected()
            appendToTerminal("[Disconnected]")
        }
    }

    override fun onCommandStarted(cmd: String) {
        handler.post { appState.setCommandStarted(cmd) }
    }

    override fun onCommandFinished(cmd: String, response: String) {
        handler.post { appState.setCommandFinished(cmd, response) }
    }

    override fun onDestroy() {
        super.onDestroy()
        serial?.disconnect()
        serial?.unregister()
    }
}
