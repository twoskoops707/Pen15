package com.android.pen15

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
    }

    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnConnect: Button
    private lateinit var btnRFID: Button
    private lateinit var btnSubGHz: Button
    private lateinit var btnTest: Button

    private var usbSerialPort: UsbSerialPort? = null
    private var isConnected = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            log("USB permission granted!")
                            device?.let { connectToDevice(it) }
                        } else {
                            log("ERROR: USB permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached - tap Connect")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB device detached")
                    disconnect()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_simple)

        // Find views
        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        progressBar = findViewById(R.id.progressBar)
        btnConnect = findViewById(R.id.btnConnect)
        btnRFID = findViewById(R.id.btnRFID)
        btnSubGHz = findViewById(R.id.btnSubGHz)
        btnTest = findViewById(R.id.btnTest)

        // Register USB receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Setup buttons
        btnConnect.setOnClickListener { connectFlipper() }
        btnRFID.setOnClickListener { sendCommand("rfid read") }
        btnSubGHz.setOnClickListener { sendCommand("subghz rx 433920000") }
        btnTest.setOnClickListener { sendCommand("device_info") }

        log("=== PEN15 PENTESTING SUITE ===")
        log("")
        log("1. Connect Flipper Zero via USB-C")
        log("2. Tap CONNECT button")
        log("3. Accept USB permission dialog")
        log("4. Use tool buttons")
        log("")
        log("Ready. Tap CONNECT to start.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        disconnect()
    }

    private fun connectFlipper() {
        log("")
        log("Searching for Flipper Zero...")

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            log("ERROR: No USB devices found!")
            log("Make sure Flipper is connected via USB-C")
            return
        }

        log("Found ${deviceList.size} USB device(s)")

        // Find Flipper (VID: 0x0483, PID: 0x5740)
        var flipperDevice: UsbDevice? = null
        for ((_, device) in deviceList) {
            log("  Device: VID=${device.vendorId} PID=${device.productId}")
            if (device.vendorId == 0x0483 && device.productId == 0x5740) {
                flipperDevice = device
                log("  ^ This is Flipper Zero!")
            }
        }

        if (flipperDevice == null) {
            // Try using usb-serial-for-android prober
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isNotEmpty()) {
                log("Found ${availableDrivers.size} serial device(s)")
                flipperDevice = availableDrivers[0].device
                log("Using: VID=${flipperDevice.vendorId} PID=${flipperDevice.productId}")
            }
        }

        if (flipperDevice == null) {
            log("ERROR: Flipper Zero not found!")
            log("Expected: VID=1155 (0x0483) PID=22336 (0x5740)")
            return
        }

        // Check permission
        if (usbManager.hasPermission(flipperDevice)) {
            log("Already have USB permission")
            connectToDevice(flipperDevice)
        } else {
            log("Requesting USB permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(flipperDevice, permissionIntent)
            log("Waiting for permission dialog...")
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        lifecycleScope.launch {
            showProgress(true)

            val result = withContext(Dispatchers.IO) {
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)

                    if (driver == null) {
                        return@withContext "ERROR: No driver for device"
                    }

                    val connection = usbManager.openDevice(driver.device)
                    if (connection == null) {
                        return@withContext "ERROR: Could not open device"
                    }

                    usbSerialPort = driver.ports[0]
                    usbSerialPort?.open(connection)
                    usbSerialPort?.setParameters(
                        115200,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    // CRITICAL: DTR must be true for STM32 CDC devices (Flipper Zero)
                    usbSerialPort?.dtr = true
                    usbSerialPort?.rts = true

                    // Small delay to let device initialize
                    Thread.sleep(100)

                    isConnected = true
                    "SUCCESS: Connected to Flipper Zero!"
                } catch (e: IOException) {
                    "ERROR: ${e.message}"
                } catch (e: Exception) {
                    "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                }
            }

            log(result)

            if (isConnected) {
                log("")
                log("Flipper Zero ready!")
                log("Try: TEST, RFID, or SUB-GHZ buttons")
                updateButtonStates()
            }

            showProgress(false)
        }
    }

    private fun sendCommand(command: String) {
        if (!isConnected || usbSerialPort == null) {
            log("ERROR: Not connected! Tap CONNECT first.")
            return
        }

        log("")
        log("> $command")

        lifecycleScope.launch {
            showProgress(true)

            val response = withContext(Dispatchers.IO) {
                try {
                    // Send command
                    val data = "$command\r".toByteArray()
                    usbSerialPort?.write(data, 2000)

                    // Read response
                    val response = StringBuilder()
                    val buffer = ByteArray(1024)
                    val startTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startTime < 3000) {
                        val numBytesRead = usbSerialPort?.read(buffer, 500) ?: 0
                        if (numBytesRead > 0) {
                            response.append(String(buffer, 0, numBytesRead))
                            if (response.contains(">:")) break
                        } else {
                            Thread.sleep(100)
                        }
                    }

                    response.toString()
                        .replace(">:", "")
                        .replace(command, "")
                        .trim()
                        .ifEmpty { "(no response - check Flipper screen)" }
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
            }

            log(response)
            showProgress(false)
        }
    }

    private fun disconnect() {
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            // Ignore
        }
        usbSerialPort = null
        isConnected = false
        updateButtonStates()
        log("Disconnected")
    }

    private fun updateButtonStates() {
        runOnUiThread {
            btnConnect.text = if (isConnected) "CONNECTED" else "CONNECT"
            btnRFID.isEnabled = isConnected
            btnSubGHz.isEnabled = isConnected
            btnTest.isEnabled = isConnected
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            outputText.append("$message\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
        Log.d(TAG, message)
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }
}
