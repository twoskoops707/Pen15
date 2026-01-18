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
import android.hardware.usb.UsbRequest
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
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740
    }

    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnConnect: Button
    private lateinit var btnRFID: Button
    private lateinit var btnSubGHz: Button
    private lateinit var btnTest: Button
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

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
                            log("Permission GRANTED")
                            device?.let { connectToDevice(it) }
                        } else {
                            log("Permission DENIED")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("Device detached")
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
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { findAndConnect() }
        btnRFID.setOnClickListener { sendCommand("rfid read") }
        btnSubGHz.setOnClickListener { sendCommand("subghz rx 433920000") }
        btnTest.setOnClickListener { sendCommand("?") }

        log("=== PEN15 v72 ===")
        log("Using UsbRequest API")
        log("")
        log("Connect Flipper, tap CONNECT")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        disconnect()
    }

    private fun findAndConnect() {
        log("")
        log("--- SCANNING ---")

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            log("No USB devices found!")
            return
        }

        var flipper: UsbDevice? = null
        for ((_, device) in deviceList) {
            log("Device: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            if (device.vendorId == FLIPPER_VID && device.productId == FLIPPER_PID) {
                flipper = device
                log("  ^ FLIPPER ZERO")
            }
        }

        if (flipper == null) {
            log("Flipper not found!")
            return
        }

        if (usbManager.hasPermission(flipper)) {
            connectToDevice(flipper)
        } else {
            log("Requesting permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(flipper, pi)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        lifecycleScope.launch {
            showProgress(true)
            log("")
            log("--- CONNECTING ---")

            val result = withContext(Dispatchers.IO) {
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

                    // Find interfaces
                    var ctrlIface: UsbInterface? = null
                    var dataIface: UsbInterface? = null
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null

                    for (i in 0 until device.interfaceCount) {
                        val iface = device.getInterface(i)
                        log("Interface $i: class=${iface.interfaceClass}")

                        if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                            ctrlIface = iface
                        }
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                            dataIface = iface
                            for (j in 0 until iface.endpointCount) {
                                val ep = iface.getEndpoint(j)
                                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                                    else outEp = ep
                                }
                            }
                        }
                    }

                    if (dataIface == null || inEp == null || outEp == null) {
                        return@withContext "ERROR: CDC interface not found"
                    }

                    log("OUT: 0x${outEp.address.toString(16)}, IN: 0x${inEp.address.toString(16)}")

                    // Open device
                    val conn = usbManager.openDevice(device)
                        ?: return@withContext "ERROR: Cannot open device"

                    // Claim interfaces
                    ctrlIface?.let {
                        if (conn.claimInterface(it, true)) log("Control claimed")
                        else log("Control claim FAILED")
                    }
                    if (!conn.claimInterface(dataIface, true)) {
                        conn.close()
                        return@withContext "ERROR: Cannot claim data interface"
                    }
                    log("Data claimed")

                    // SET_LINE_CODING (115200 8N1)
                    val lineCoding = byteArrayOf(
                        0x00, 0xC2.toByte(), 0x01, 0x00,  // 115200
                        0x00, 0x00, 0x08
                    )
                    var r = conn.controlTransfer(0x21, 0x20, 0, 0, lineCoding, 7, 1000)
                    log("SET_LINE_CODING: $r")

                    // SET_CONTROL_LINE_STATE - NO DTR/RTS (causes timeouts!)
                    r = conn.controlTransfer(0x21, 0x22, 0x00, 0, null, 0, 1000)
                    log("SET_CONTROL (no DTR): $r")

                    // Store
                    usbConnection = conn
                    controlInterface = ctrlIface
                    dataInterface = dataIface
                    endpointIn = inEp
                    endpointOut = outEp

                    Thread.sleep(500)

                    // Test with UsbRequest
                    log("Testing UsbRequest write...")
                    val testData = "\r\n".toByteArray()
                    val buffer = ByteBuffer.allocate(64)
                    buffer.put(testData)

                    val request = UsbRequest()
                    if (!request.initialize(conn, outEp)) {
                        return@withContext "ERROR: UsbRequest init failed"
                    }

                    buffer.rewind()
                    if (!request.queue(buffer, testData.size)) {
                        request.close()
                        return@withContext "ERROR: UsbRequest queue failed"
                    }

                    val completed = conn.requestWait()
                    if (completed == null) {
                        request.close()
                        return@withContext "ERROR: UsbRequest timeout"
                    }
                    request.close()
                    log("UsbRequest write OK!")

                    // Drain
                    Thread.sleep(200)
                    val drainBuf = ByteArray(512)
                    var drained = 0
                    for (i in 0..3) {
                        val n = conn.bulkTransfer(inEp, drainBuf, 512, 100)
                        if (n > 0) drained += n else break
                    }
                    if (drained > 0) log("Drained $drained bytes")

                    isConnected = true
                    "SUCCESS!"
                } catch (e: Exception) {
                    Log.e(TAG, "Connect error", e)
                    "ERROR: ${e.message}"
                }
            }

            log(result)
            if (isConnected) {
                log("")
                log("Ready! Tap TEST")
                updateUI()
            }
            showProgress(false)
        }
    }

    private fun sendCommand(cmd: String) {
        if (!isConnected) {
            log("Not connected!")
            return
        }

        log("")
        log("> $cmd")

        lifecycleScope.launch {
            showProgress(true)

            val response = withContext(Dispatchers.IO) {
                try {
                    val conn = usbConnection ?: return@withContext "No connection"
                    val epOut = endpointOut ?: return@withContext "No OUT endpoint"
                    val epIn = endpointIn ?: return@withContext "No IN endpoint"

                    // Clear buffer
                    val clearBuf = ByteArray(512)
                    for (i in 0..2) {
                        val n = conn.bulkTransfer(epIn, clearBuf, 512, 50)
                        if (n <= 0) break
                    }

                    // Send using UsbRequest
                    val cmdBytes = "$cmd\r\n".toByteArray()
                    val outBuffer = ByteBuffer.allocate(cmdBytes.size)
                    outBuffer.put(cmdBytes)
                    outBuffer.rewind()

                    val outRequest = UsbRequest()
                    if (!outRequest.initialize(conn, epOut)) {
                        return@withContext "Write init failed"
                    }

                    if (!outRequest.queue(outBuffer, cmdBytes.size)) {
                        outRequest.close()
                        return@withContext "Write queue failed"
                    }

                    val writeResult = conn.requestWait()
                    outRequest.close()

                    if (writeResult == null) {
                        return@withContext "Write timeout"
                    }

                    log("Sent ${cmdBytes.size} bytes")
                    Thread.sleep(100)

                    // Read response using bulk transfer
                    val response = StringBuilder()
                    val readBuf = ByteArray(512)
                    val startTime = System.currentTimeMillis()
                    var totalRead = 0

                    while (System.currentTimeMillis() - startTime < 3000) {
                        val n = conn.bulkTransfer(epIn, readBuf, 512, 300)
                        if (n > 0) {
                            response.append(String(readBuf, 0, n))
                            totalRead += n
                            if (response.contains(">:")) break
                        } else if (totalRead > 0) {
                            break
                        }
                        Thread.sleep(50)
                    }

                    if (response.isEmpty()) {
                        "(no response, read $totalRead bytes)"
                    } else {
                        // Clean up
                        response.toString()
                            .lines()
                            .filterNot { it.contains(cmd) }
                            .joinToString("\n")
                            .replace(Regex(">:\\s*$"), "")
                            .trim()
                            .ifEmpty { "(empty response)" }
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
        runOnUiThread { updateUI() }
    }

    private fun updateUI() {
        btnConnect.text = if (isConnected) "CONNECTED" else "CONNECT"
        btnConnect.isEnabled = !isConnected
        btnRFID.isEnabled = isConnected
        btnSubGHz.isEnabled = isConnected
        btnTest.isEnabled = isConnected

        // Update status indicator
        if (isConnected) {
            statusDot.setBackgroundResource(R.drawable.indicator_status_connected)
            statusText.text = "ONLINE"
            statusText.setTextColor(resources.getColor(R.color.status_connected, null))
        } else {
            statusDot.setBackgroundResource(R.drawable.indicator_status)
            statusText.text = "OFFLINE"
            statusText.setTextColor(resources.getColor(R.color.text_secondary, null))
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            outputText.append("$msg\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
        Log.d(TAG, msg)
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread { progressBar.visibility = if (show) View.VISIBLE else View.GONE }
    }
}
