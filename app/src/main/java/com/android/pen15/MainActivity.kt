package com.android.pen15

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHostFragment.navController)

        serial = FlipperSerial(this).also {
            it.listener = this
            it.register()
        }

        appendToTerminal("=== PEN15 v2.0 ===")
        appendToTerminal("Flipper Zero + AWOK Marauder Controller")
        appendToTerminal("Go to Status tab to connect")
    }

    fun sendCommand(cmd: String) {
        appendToTerminal("> $cmd")
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

    override fun onDestroy() {
        super.onDestroy()
        serial?.disconnect()
        serial?.unregister()
    }
}
