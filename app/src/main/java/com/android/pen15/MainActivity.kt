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
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740
        private const val KEEP_ALIVE_INTERVAL_MS = 2000L
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
    private val isConnected = AtomicBoolean(false)

    // Keep-alive handler
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (isConnected.get()) {
                performKeepAlive()
                keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
            }
        }
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

        log("=== PEN15 v73 ===")
        log("With keep-alive & stability fixes")
        log("")
        log("Connect Flipper, tap CONNECT")
    }

    override fun onDestroy() {
        super.onDestroy()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
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

                    // Claim control interface first
                    ctrlIface?.let {
                        if (conn.claimInterface(it, true)) log("Control claimed")
                        else log("Control claim FAILED")
                    }

                    // Then claim data interface
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

                    // SET_CONTROL_LINE_STATE - NO DTR/RTS (prevents timeouts!)
                    r = conn.controlTransfer(0x21, 0x22, 0x00, 0, null, 0, 1000)
                    log("SET_CONTROL (no DTR): $r")

                    // Store references
                    usbConnection = conn
                    controlInterface = ctrlIface
                    dataInterface = dataIface
                    endpointIn = inEp
                    endpointOut = outEp

                    Thread.sleep(300)

                    // Initial drain of any pending data
                    val drainBuf = ByteArray(512)
                    var drained = 0
                    for (i in 0..5) {
                        val n = conn.bulkTransfer(inEp, drainBuf, 512, 100)
                        if (n > 0) drained += n else break
                    }
                    if (drained > 0) log("Initial drain: $drained bytes")

                    // Send initial newline to wake up CLI
                    val initData = "\r\n".toByteArray()
                    val writeResult = conn.bulkTransfer(outEp, initData, initData.size, 1000)
                    if (writeResult < 0) {
                        conn.close()
                        return@withContext "ERROR: Initial write failed ($writeResult)"
                    }
                    log("Initial write: $writeResult bytes")

                    Thread.sleep(200)

                    // Drain response
                    drained = 0
                    for (i in 0..5) {
                        val n = conn.bulkTransfer(inEp, drainBuf, 512, 100)
                        if (n > 0) drained += n else break
                    }
                    if (drained > 0) log("Response drain: $drained bytes")

                    isConnected.set(true)
                    "SUCCESS!"
                } catch (e: Exception) {
                    Log.e(TAG, "Connect error", e)
                    "ERROR: ${e.message}"
                }
            }

            log(result)
            if (isConnected.get()) {
                log("")
                log("Ready! Keep-alive active.")
                log("Tap TEST to verify")
                updateUI()
                // Start keep-alive
                keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
            }
            showProgress(false)
        }
    }

    private fun performKeepAlive() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = usbConnection ?: return@launch
                val epIn = endpointIn ?: return@launch

                // Just read any pending data - this keeps the connection active
                val buf = ByteArray(64)
                val n = conn.bulkTransfer(epIn, buf, 64, 50)
                if (n > 0) {
                    Log.d(TAG, "Keep-alive read: $n bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Keep-alive error", e)
                // Connection might be dead
                if (isConnected.get()) {
                    isConnected.set(false)
                    runOnUiThread {
                        log("Connection lost!")
                        disconnect()
                    }
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        if (!isConnected.get()) {
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

                    // Clear any pending data first
                    val clearBuf = ByteArray(512)
                    for (i in 0..3) {
                        val n = conn.bulkTransfer(epIn, clearBuf, 512, 50)
                        if (n <= 0) break
                    }

                    // Send command with CR termination (Flipper expects \r)
                    val cmdBytes = "$cmd\r".toByteArray()
                    val writeResult = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, 2000)

                    if (writeResult < 0) {
                        // Try to recover
                        log("Write failed ($writeResult), retrying...")
                        Thread.sleep(100)
                        val retryResult = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, 2000)
                        if (retryResult < 0) {
                            return@withContext "Write failed: $retryResult"
                        }
                    }

                    log("Sent ${cmdBytes.size} bytes")
                    Thread.sleep(100)

                    // Read response
                    val response = StringBuilder()
                    val readBuf = ByteArray(512)
                    val startTime = System.currentTimeMillis()
                    var totalRead = 0
                    var noDataCount = 0

                    while (System.currentTimeMillis() - startTime < 5000) {
                        val n = conn.bulkTransfer(epIn, readBuf, 512, 200)
                        if (n > 0) {
                            val chunk = String(readBuf, 0, n)
                            response.append(chunk)
                            totalRead += n
                            noDataCount = 0

                            // Check for prompt indicating command complete
                            if (response.contains(">:") || response.contains("\r\n>")) {
                                break
                            }
                        } else {
                            noDataCount++
                            // If we've read some data and now getting nothing, probably done
                            if (totalRead > 0 && noDataCount >= 3) {
                                break
                            }
                        }
                        Thread.sleep(30)
                    }

                    log("Read $totalRead bytes")

                    if (response.isEmpty()) {
                        "(no response)"
                    } else {
                        // Clean up response
                        response.toString()
                            .replace("\r\n", "\n")
                            .replace("\r", "\n")
                            .lines()
                            .filterNot { it.trim() == cmd || it.trim().isEmpty() }
                            .joinToString("\n")
                            .replace(Regex(">:\\s*$"), "")
                            .replace(Regex("^\\s*>:\\s*"), "")
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
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        isConnected.set(false)
        try {
            dataInterface?.let { usbConnection?.releaseInterface(it) }
            controlInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
        usbConnection = null
        controlInterface = null
        dataInterface = null
        endpointIn = null
        endpointOut = null
        runOnUiThread { updateUI() }
    }

    private fun updateUI() {
        val connected = isConnected.get()
        btnConnect.text = if (connected) "CONNECTED" else "CONNECT"
        btnConnect.isEnabled = !connected
        btnRFID.isEnabled = connected
        btnSubGHz.isEnabled = connected
        btnTest.isEnabled = connected

        // Update status indicator
        if (connected) {
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
