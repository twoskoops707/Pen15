package com.android.pen15

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * PEN15 v88 - Clean rebuild based on SimpleUsbTerminal patterns
 * Reference: https://github.com/kai-morich/SimpleUsbTerminal
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"

        // Flipper Zero USB IDs
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740

        // Serial settings (Flipper CLI)
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT = 200
    }

    // UI
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnSend: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var deviceInfo: TextView

    // Tool buttons
    private lateinit var btnRfid: MaterialButton
    private lateinit var btnSubghz: MaterialButton
    private lateinit var btnIbutton: MaterialButton
    private lateinit var btnIr: MaterialButton

    // USB
    private var usbManager: UsbManager? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // USB permission receiver
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
                            device?.let { connectToDevice(it) }
                        } else {
                            log("USB permission denied")
                        }
                    }
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
        setContentView(R.layout.activity_main_v2)

        initViews()
        setupListeners()
        registerUsbReceiver()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        log("=== PEN15 v88 ===")
        log("Connect Flipper via USB-C OTG")
        log("")
    }

    private fun initViews() {
        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)
        statusText = findViewById(R.id.statusText)
        deviceInfo = findViewById(R.id.deviceInfo)

        btnRfid = findViewById(R.id.btnRfid)
        btnSubghz = findViewById(R.id.btnSubghz)
        btnIbutton = findViewById(R.id.btnIbutton)
        btnIr = findViewById(R.id.btnIr)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (connected) {
                disconnect()
            } else {
                scanAndConnect()
            }
        }

        btnSend.setOnClickListener {
            val cmd = inputField.text.toString().trim()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                inputField.text.clear()
            }
        }

        // Tool buttons
        btnRfid.setOnClickListener { sendCommand("rfid read") }
        btnSubghz.setOnClickListener { sendCommand("subghz rx") }
        btnIbutton.setOnClickListener { sendCommand("ikey read") }
        btnIr.setOnClickListener { sendCommand("ir rx") }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    // ==================
    // USB CONNECTION
    // ==================

    private fun scanAndConnect() {
        log("Scanning for USB devices...")

        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            log("No USB serial devices found")

            // Show raw USB devices for debug
            manager.deviceList.forEach { (_, device) ->
                log("  Raw: VID=${String.format("0x%04X", device.vendorId)} PID=${String.format("0x%04X", device.productId)}")
            }
            return
        }

        // Find Flipper Zero
        val driver = drivers.find {
            it.device.vendorId == FLIPPER_VID && it.device.productId == FLIPPER_PID
        } ?: drivers[0]

        val device = driver.device
        log("Found: ${device.productName ?: "USB Device"}")
        log("  VID=${String.format("0x%04X", device.vendorId)} PID=${String.format("0x%04X", device.productId)}")

        // Check permission
        if (manager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            log("Requesting USB permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        log("Connecting...")

        try {
            val manager = usbManager ?: throw Exception("USB Manager null")
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw Exception("No driver for device")

            usbConnection = manager.openDevice(device)
                ?: throw Exception("Cannot open device")

            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // CRITICAL: Set DTR/RTS for CDC-ACM
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true

            log("Port: $BAUD_RATE 8N1, DTR=ON, RTS=ON")

            // Start IO Manager with listener
            ioManager = SerialInputOutputManager(usbSerialPort, this)
            ioManager?.start()

            connected = true
            updateUI()

            // Wake CLI with newline
            mainHandler.postDelayed({
                usbSerialPort?.write("\r\n".toByteArray(), WRITE_TIMEOUT)
            }, 100)

            log("Connected!")

        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            log("ERROR: ${e.message}")
            disconnect()
        }
    }

    private fun disconnect() {
        connected = false

        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            usbSerialPort?.dtr = false
            usbSerialPort?.rts = false
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing port", e)
        }
        usbSerialPort = null

        try {
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
        usbConnection = null

        updateUI()
        log("Disconnected")
    }

    // ==================
    // SERIAL I/O
    // ==================

    override fun onNewData(data: ByteArray) {
        val text = String(data)
        Log.d(TAG, "RX [${data.size}]: $text")

        mainHandler.post {
            val clean = text.replace("\r\n", "\n").replace("\r", "")
            if (clean.isNotBlank()) {
                outputText.append(clean)
                scrollToBottom()
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "IO Error", e)
        mainHandler.post {
            log("IO Error: ${e.message}")
            disconnect()
        }
    }

    private fun sendCommand(cmd: String) {
        if (!connected) {
            log("Not connected")
            return
        }

        log("> $cmd")

        try {
            usbSerialPort?.write("$cmd\r".toByteArray(), WRITE_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            log("Send error: ${e.message}")
        }
    }

    // ==================
    // UI
    // ==================

    private fun updateUI() {
        btnConnect.text = if (connected) "DISCONNECT" else "CONNECT"
        statusText.text = if (connected) "CONNECTED" else "OFFLINE"
        statusText.setTextColor(getColor(if (connected) R.color.green else R.color.gray))

        deviceInfo.text = if (connected) {
            "Flipper Zero @ $BAUD_RATE"
        } else {
            "No device"
        }

        // Enable/disable tool buttons
        btnRfid.isEnabled = connected
        btnSubghz.isEnabled = connected
        btnIbutton.isEnabled = connected
        btnIr.isEnabled = connected
        btnSend.isEnabled = connected
        inputField.isEnabled = connected
    }

    private fun log(msg: String) {
        mainHandler.post {
            outputText.append("$msg\n")

            // Keep last 100 lines
            val lines = outputText.text.toString().lines()
            if (lines.size > 100) {
                outputText.text = lines.takeLast(100).joinToString("\n")
            }

            scrollToBottom()
        }
        Log.d(TAG, msg)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
