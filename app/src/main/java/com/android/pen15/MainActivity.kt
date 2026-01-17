package com.android.pen15

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Raw USB connection
    private var usbConnection: UsbDeviceConnection? = null
    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
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
                            log("USB permission GRANTED")
                            device?.let { connectToDevice(it) }
                        } else {
                            log("ERROR: USB permission DENIED")
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
        btnTest.setOnClickListener { sendCommand("?") }

        log("=== PEN15 v71 (RAW USB) ===")
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
        log("--- SCANNING USB ---")

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        log("Found ${deviceList.size} USB device(s)")

        if (deviceList.isEmpty()) {
            log("ERROR: No USB devices!")
            log("Connect Flipper via USB-C")
            return
        }

        var flipperDevice: UsbDevice? = null

        for ((name, device) in deviceList) {
            val vid = device.vendorId
            val pid = device.productId
            log("  $name")
            log("    VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")
            log("    Interfaces: ${device.interfaceCount}")

            if (vid == FLIPPER_VID && pid == FLIPPER_PID) {
                flipperDevice = device
                log("    >>> FLIPPER ZERO <<<")
            }
        }

        if (flipperDevice == null) {
            log("")
            log("ERROR: Flipper not found!")
            log("Expected VID=0x0483 PID=0x5740")
            return
        }

        // Request permission
        if (usbManager.hasPermission(flipperDevice)) {
            log("Permission OK")
            connectToDevice(flipperDevice)
        } else {
            log("Requesting permission...")
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
            log("")
            log("--- CONNECTING (RAW USB) ---")

            val result = withContext(Dispatchers.IO) {
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

                    // Find BOTH CDC interfaces
                    var ctrlIface: UsbInterface? = null
                    var dataIface: UsbInterface? = null
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null

                    log("Scanning ${device.interfaceCount} interfaces...")

                    for (i in 0 until device.interfaceCount) {
                        val iface = device.getInterface(i)
                        log("  Interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass}")

                        // CDC Control class = 0x02
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                            ctrlIface = iface
                            log("    ^ CDC Control")
                        }

                        // CDC Data class = 0x0A (10)
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                            iface.interfaceClass == 0x0A) {

                            for (j in 0 until iface.endpointCount) {
                                val ep = iface.getEndpoint(j)
                                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                                log("    EP$j: ${dir} addr=0x${ep.address.toString(16)} type=${ep.type}")

                                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                                        inEp = ep
                                    } else {
                                        outEp = ep
                                    }
                                }
                            }

                            if (inEp != null && outEp != null) {
                                dataIface = iface
                                log("    ^ CDC Data")
                            }
                        }
                    }

                    if (dataIface == null || inEp == null || outEp == null) {
                        return@withContext "ERROR: CDC Data interface not found"
                    }

                    log("IN endpoint: 0x${inEp.address.toString(16)}")
                    log("OUT endpoint: 0x${outEp.address.toString(16)}")

                    // Open connection
                    val connection = usbManager.openDevice(device)
                    if (connection == null) {
                        return@withContext "ERROR: openDevice failed"
                    }

                    // MUST claim Control interface FIRST (if present)
                    if (ctrlIface != null) {
                        if (!connection.claimInterface(ctrlIface, true)) {
                            log("WARN: Control interface claim failed")
                        } else {
                            log("Control interface claimed")
                        }
                    }

                    // Claim Data interface
                    if (!connection.claimInterface(dataIface, true)) {
                        connection.close()
                        return@withContext "ERROR: Data interface claim failed"
                    }
                    log("Data interface claimed")

                    // Set line coding (115200, 8N1)
                    val lineCoding = byteArrayOf(
                        0x00, 0xC2.toByte(), 0x01, 0x00,  // 115200 baud
                        0x00,  // 1 stop bit
                        0x00,  // no parity
                        0x08   // 8 data bits
                    )

                    val lcResult = connection.controlTransfer(
                        0x21, 0x20, 0, 0, lineCoding, lineCoding.size, 1000
                    )
                    log("SET_LINE_CODING: $lcResult")

                    // Set control line state (DTR=0, RTS=0)
                    val clsResult = connection.controlTransfer(
                        0x21, 0x22, 0x00, 0, null, 0, 1000
                    )
                    log("SET_CONTROL_LINE_STATE: $clsResult")

                    // Store connection
                    usbConnection = connection
                    controlInterface = ctrlIface
                    dataInterface = dataIface
                    endpointIn = inEp
                    endpointOut = outEp
                    isConnected = true

                    // Wait for connection to stabilize
                    Thread.sleep(300)

                    // Try a simple bulk write test
                    val testBuf = "\r".toByteArray()
                    val testResult = connection.bulkTransfer(outEp, testBuf, testBuf.size, 1000)
                    log("Test write: $testResult")

                    if (testResult < 0) {
                        // Try releasing and reclaiming
                        log("Reclaiming interfaces...")
                        connection.releaseInterface(dataIface)
                        ctrlIface?.let { connection.releaseInterface(it) }
                        Thread.sleep(100)
                        ctrlIface?.let { connection.claimInterface(it, true) }
                        connection.claimInterface(dataIface, true)
                        Thread.sleep(100)

                        val retry = connection.bulkTransfer(outEp, testBuf, testBuf.size, 1000)
                        log("Retry write: $retry")
                    }

                    // Drain pending data
                    val drainBuf = ByteArray(512)
                    var drained = 0
                    for (i in 0..5) {
                        val n = connection.bulkTransfer(inEp, drainBuf, drainBuf.size, 100)
                        if (n > 0) drained += n else break
                    }
                    if (drained > 0) log("Drained $drained bytes")

                    "SUCCESS: Connected!"
                } catch (e: Exception) {
                    Log.e(TAG, "Connection error", e)
                    isConnected = false
                    "ERROR: ${e.message}"
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
        if (!isConnected) {
            log("Not connected!")
            return
        }

        log("")
        log("> $command")

        lifecycleScope.launch {
            showProgress(true)

            val response = withContext(Dispatchers.IO) {
                try {
                    val conn = usbConnection ?: return@withContext "ERROR: no connection"
                    val epIn = endpointIn ?: return@withContext "ERROR: no IN endpoint"
                    val epOut = endpointOut ?: return@withContext "ERROR: no OUT endpoint"

                    // Clear pending data
                    val clearBuf = ByteArray(512)
                    for (i in 0..2) {
                        val n = conn.bulkTransfer(epIn, clearBuf, clearBuf.size, 50)
                        if (n <= 0) break
                    }

                    // Send command with CRLF
                    val cmdData = "$command\r\n".toByteArray()
                    val written = conn.bulkTransfer(epOut, cmdData, cmdData.size, 2000)
                    Log.d(TAG, "Wrote $written bytes")

                    if (written < 0) {
                        return@withContext "ERROR: Write failed ($written)"
                    }

                    Thread.sleep(100)

                    // Read response
                    val response = StringBuilder()
                    val buffer = ByteArray(512)
                    val startTime = System.currentTimeMillis()
                    var totalBytes = 0
                    var emptyReads = 0

                    while (System.currentTimeMillis() - startTime < 3000) {
                        val len = conn.bulkTransfer(epIn, buffer, buffer.size, 300)

                        if (len > 0) {
                            val chunk = String(buffer, 0, len)
                            response.append(chunk)
                            totalBytes += len
                            emptyReads = 0
                            Log.d(TAG, "Read $len: ${chunk.take(30)}")

                            // Check for prompt
                            if (response.contains(">:")) {
                                break
                            }
                        } else {
                            emptyReads++
                            if (emptyReads > 5 && totalBytes > 0) break
                        }

                        Thread.sleep(30)
                    }

                    // Clean response
                    var result = response.toString()
                    val lines = result.lines().toMutableList()

                    // Remove echo line
                    if (lines.isNotEmpty() && lines[0].contains(command)) {
                        lines.removeAt(0)
                    }

                    result = lines.joinToString("\n")
                        .replace(Regex(">:\\s*$"), "")
                        .trim()

                    if (result.isEmpty()) {
                        "(received $totalBytes bytes)"
                    } else {
                        result
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Command error", e)
                    "ERROR: ${e.message}"
                }
            }

            log(response)
            showProgress(false)
        }
    }

    private fun disconnect() {
        try {
            dataInterface?.let { usbConnection?.releaseInterface(it) }
            controlInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {}
        usbConnection = null
        controlInterface = null
        dataInterface = null
        endpointIn = null
        endpointOut = null
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
