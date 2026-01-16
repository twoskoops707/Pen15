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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
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

        // Flipper Zero USB IDs
        private const val FLIPPER_VID = 0x0483  // STMicroelectronics
        private const val FLIPPER_PID = 0x5740  // Virtual COM Port
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

    // Custom prober that explicitly recognizes Flipper Zero
    private val flipperProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        table.addProduct(FLIPPER_VID, FLIPPER_PID, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

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
                            log("USB permission GRANTED")
                            device?.let { connectToDevice(it) }
                        } else {
                            log("ERROR: USB permission DENIED by user")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached")
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

        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        progressBar = findViewById(R.id.progressBar)
        btnConnect = findViewById(R.id.btnConnect)
        btnRFID = findViewById(R.id.btnRFID)
        btnSubGHz = findViewById(R.id.btnSubGHz)
        btnTest = findViewById(R.id.btnTest)

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

        btnConnect.setOnClickListener { connectFlipper() }
        btnRFID.setOnClickListener { sendCommand("rfid read") }
        btnSubGHz.setOnClickListener { sendCommand("subghz rx 433920000") }
        btnTest.setOnClickListener { sendCommand("?") }  // Simple help command

        log("=== PEN15 v66 ===")
        log("")
        log("Connect Flipper via USB-C")
        log("Then tap CONNECT")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        disconnect()
    }

    private fun connectFlipper() {
        log("")
        log("--- CONNECTING ---")

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        log("USB devices: ${deviceList.size}")

        if (deviceList.isEmpty()) {
            log("ERROR: No USB devices!")
            log("Is Flipper connected via USB-C?")
            return
        }

        // Find Flipper Zero
        var flipperDevice: UsbDevice? = null

        for ((name, device) in deviceList) {
            val vid = device.vendorId
            val pid = device.productId
            log("  $name: VID=$vid PID=$pid")

            if (vid == FLIPPER_VID && pid == FLIPPER_PID) {
                flipperDevice = device
                log("  ^ FLIPPER ZERO FOUND!")
            }
        }

        // Also try the default prober
        if (flipperDevice == null) {
            log("Trying default prober...")
            val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (defaultDrivers.isNotEmpty()) {
                flipperDevice = defaultDrivers[0].device
                log("Found via default prober: VID=${flipperDevice.vendorId}")
            }
        }

        // Try custom Flipper prober
        if (flipperDevice == null) {
            log("Trying Flipper prober...")
            val flipperDrivers = flipperProber.findAllDrivers(usbManager)
            if (flipperDrivers.isNotEmpty()) {
                flipperDevice = flipperDrivers[0].device
                log("Found via Flipper prober")
            }
        }

        if (flipperDevice == null) {
            log("")
            log("ERROR: Flipper not found!")
            log("Expected: VID=1155 PID=22336")
            return
        }

        // Request permission
        if (usbManager.hasPermission(flipperDevice)) {
            log("Already have permission")
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
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        lifecycleScope.launch {
            showProgress(true)
            log("Opening connection...")

            val result = withContext(Dispatchers.IO) {
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

                    // Try custom prober first, then default
                    var driver = flipperProber.probeDevice(device)
                    if (driver == null) {
                        driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    }

                    // Direct instantiation as fallback
                    if (driver == null) {
                        driver = CdcAcmSerialDriver(device)
                    }

                    val connection = usbManager.openDevice(device)
                    if (connection == null) {
                        return@withContext "ERROR: openDevice returned null"
                    }

                    val port = driver.ports[0]
                    port.open(connection)

                    // Set parameters - NO DTR/RTS initially (can cause reset!)
                    port.setParameters(
                        115200,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )

                    usbSerialPort = port
                    isConnected = true

                    // Enable DTR for data flow (required for STM32 CDC)
                    try {
                        port.dtr = true
                    } catch (e: Exception) {
                        // Some drivers don't support DTR
                    }

                    // Small delay to stabilize
                    Thread.sleep(300)

                    "SUCCESS: Connected!"
                } catch (e: IOException) {
                    "ERROR (IO): ${e.message}"
                } catch (e: Exception) {
                    "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                }
            }

            log(result)

            if (isConnected) {
                log("")
                log("Ready! Try TEST button")
                updateButtonStates()
            }

            showProgress(false)
        }
    }

    private fun sendCommand(command: String) {
        if (!isConnected || usbSerialPort == null) {
            log("Not connected!")
            return
        }

        log("")
        log("> $command")

        lifecycleScope.launch {
            showProgress(true)

            val response = withContext(Dispatchers.IO) {
                try {
                    val port = usbSerialPort ?: return@withContext "ERROR: port is null"

                    // Clear any pending data
                    val clearBuf = ByteArray(1024)
                    try { port.read(clearBuf, 100) } catch (e: Exception) {}

                    // Send command with carriage return
                    val data = "$command\r".toByteArray()
                    port.write(data, 1000)

                    // Read response
                    val response = StringBuilder()
                    val buffer = ByteArray(1024)
                    val startTime = System.currentTimeMillis()
                    var totalBytes = 0

                    while (System.currentTimeMillis() - startTime < 2000) {
                        try {
                            val len = port.read(buffer, 200)
                            if (len > 0) {
                                val chunk = String(buffer, 0, len)
                                response.append(chunk)
                                totalBytes += len

                                // Check for prompt
                                if (response.contains(">:") || response.contains(">: ")) {
                                    break
                                }
                            }
                        } catch (e: IOException) {
                            break
                        }
                        Thread.sleep(50)
                    }

                    val result = response.toString()
                        .replace(Regex("^.*$command"), "")  // Remove echo
                        .replace(">:", "")
                        .replace(">: ", "")
                        .trim()

                    if (result.isEmpty()) {
                        "(no response - read $totalBytes bytes)"
                    } else {
                        result
                    }
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
        } catch (e: Exception) {}
        usbSerialPort = null
        isConnected = false
        runOnUiThread { updateButtonStates() }
    }

    private fun updateButtonStates() {
        btnConnect.text = if (isConnected) "CONNECTED" else "CONNECT"
        btnConnect.isEnabled = !isConnected
        btnRFID.isEnabled = isConnected
        btnSubGHz.isEnabled = isConnected
        btnTest.isEnabled = isConnected
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
