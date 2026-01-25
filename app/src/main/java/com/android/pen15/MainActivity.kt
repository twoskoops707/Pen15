package com.android.pen15

import android.app.AlertDialog
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
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * PEN15 v90 - Complete Flipper Zero Controller
 * USB Serial communication with Flipper Zero CLI
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT = 200
    }

    // UI - Header
    private lateinit var statusChip: Chip
    private lateinit var deviceInfo: TextView

    // UI - Terminal
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnClear: MaterialButton

    // UI - Connection
    private lateinit var btnConnect: MaterialButton

    // UI - Tool Buttons (Row 1: RFID, NFC, SubGHz, IR)
    private lateinit var btnRfid: MaterialButton
    private lateinit var btnNfc: MaterialButton
    private lateinit var btnSubghz: MaterialButton
    private lateinit var btnIr: MaterialButton

    // UI - Tool Buttons (Row 2: iButton, GPIO, BadUSB, Storage)
    private lateinit var btnIbutton: MaterialButton
    private lateinit var btnGpio: MaterialButton
    private lateinit var btnBadusb: MaterialButton
    private lateinit var btnStorage: MaterialButton

    // UI - Quick Actions
    private lateinit var btnInfo: MaterialButton
    private lateinit var btnReboot: MaterialButton

    // USB
    private var usbManager: UsbManager? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // SubGHz frequencies (common)
    private val subghzFrequencies = arrayOf(
        "315.00 MHz" to "315000000",
        "433.92 MHz" to "433920000",
        "868.35 MHz" to "868350000",
        "915.00 MHz" to "915000000"
    )

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
        setContentView(R.layout.activity_main_v3)

        initViews()
        setupListeners()
        registerUsbReceiver()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        log("=== PEN15 v90 ===")
        log("Flipper Zero Controller")
        log("")
        log("Connect Flipper via USB-C OTG")
        log("Then tap CONNECT")
    }

    private fun initViews() {
        // Header
        statusChip = findViewById(R.id.statusChip)
        deviceInfo = findViewById(R.id.deviceInfo)

        // Terminal
        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        btnSend = findViewById(R.id.btnSend)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)

        // Connection
        btnConnect = findViewById(R.id.btnConnect)

        // Tool Row 1
        btnRfid = findViewById(R.id.btnRfid)
        btnNfc = findViewById(R.id.btnNfc)
        btnSubghz = findViewById(R.id.btnSubghz)
        btnIr = findViewById(R.id.btnIr)

        // Tool Row 2
        btnIbutton = findViewById(R.id.btnIbutton)
        btnGpio = findViewById(R.id.btnGpio)
        btnBadusb = findViewById(R.id.btnBadusb)
        btnStorage = findViewById(R.id.btnStorage)

        // Quick Actions
        btnInfo = findViewById(R.id.btnInfo)
        btnReboot = findViewById(R.id.btnReboot)
    }

    private fun setupListeners() {
        // Connection
        btnConnect.setOnClickListener {
            if (connected) disconnect() else scanAndConnect()
        }

        // Terminal controls
        btnSend.setOnClickListener {
            val cmd = inputField.text.toString().trim()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                inputField.text.clear()
            }
        }
        btnStop.setOnClickListener { sendCtrlC() }
        btnClear.setOnClickListener { clearTerminal() }

        // Row 1 tools
        btnRfid.setOnClickListener { showRfidMenu() }
        btnNfc.setOnClickListener { showNfcMenu() }
        btnSubghz.setOnClickListener { showSubghzMenu() }
        btnIr.setOnClickListener { showIrMenu() }

        // Row 2 tools
        btnIbutton.setOnClickListener { sendCommand("ikey read") }
        btnGpio.setOnClickListener { showGpioMenu() }
        btnBadusb.setOnClickListener { showBadusbMenu() }
        btnStorage.setOnClickListener { showStorageMenu() }

        // Quick Actions
        btnInfo.setOnClickListener { sendCommand("device_info") }
        btnReboot.setOnClickListener { confirmReboot() }
    }

    // ==================
    // TOOL MENUS
    // ==================

    private fun showRfidMenu() {
        val options = arrayOf("Read Card", "Emulate Last", "List Saved")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("RFID (125kHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("rfid read")
                    1 -> sendCommand("rfid emulate")
                    2 -> sendCommand("storage list /ext/lfrfid")
                }
            }.show()
    }

    private fun showNfcMenu() {
        val options = arrayOf("Detect Card", "Read Full", "Emulate Last", "MFKey32", "List Saved")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("NFC (13.56MHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("nfc detect")
                    1 -> sendCommand("nfc read")
                    2 -> sendCommand("nfc emulate")
                    3 -> sendCommand("nfc mfkey32")
                    4 -> sendCommand("storage list /ext/nfc")
                }
            }.show()
    }

    private fun showSubghzMenu() {
        val options = arrayOf("RX (Listen)", "TX Last Signal", "Frequency Analyzer", "Choose Frequency...", "List Saved")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Sub-GHz")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("subghz rx")
                    1 -> sendCommand("subghz tx")
                    2 -> sendCommand("subghz rx 433920000") // Common freq
                    3 -> showFrequencyPicker()
                    4 -> sendCommand("storage list /ext/subghz")
                }
            }.show()
    }

    private fun showFrequencyPicker() {
        val freqNames = subghzFrequencies.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Select Frequency")
            .setItems(freqNames) { _, which ->
                val freq = subghzFrequencies[which].second
                sendCommand("subghz rx $freq")
            }.show()
    }

    private fun showIrMenu() {
        val options = arrayOf("Receive Signal", "TX Last", "Universal Remote", "List Saved")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Infrared")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("ir rx")
                    1 -> sendCommand("ir tx")
                    2 -> sendCommand("storage list /ext/infrared")
                    3 -> sendCommand("storage list /ext/infrared")
                }
            }.show()
    }

    private fun showGpioMenu() {
        val options = arrayOf("Enable 5V", "Enable 3.3V", "Disable Power", "GPIO Status")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("GPIO")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("power 5v 1")
                    1 -> sendCommand("power 3v3 1")
                    2 -> sendCommand("power 5v 0")
                    3 -> sendCommand("gpio")
                }
            }.show()
    }

    private fun showBadusbMenu() {
        val options = arrayOf("List Payloads", "Run Last", "Demo Script")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("BadUSB")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("storage list /ext/badusb")
                    1 -> {
                        log("BadUSB requires GUI interaction")
                        log("Use Flipper screen to run payloads")
                    }
                    2 -> sendCommand("storage read /ext/badusb/demo.txt")
                }
            }.show()
    }

    private fun showStorageMenu() {
        val options = arrayOf("SD Card Root", "Saved RFID", "Saved NFC", "Saved SubGHz", "Saved IR", "Free Space")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Storage")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("storage list /ext")
                    1 -> sendCommand("storage list /ext/lfrfid")
                    2 -> sendCommand("storage list /ext/nfc")
                    3 -> sendCommand("storage list /ext/subghz")
                    4 -> sendCommand("storage list /ext/infrared")
                    5 -> sendCommand("storage info /ext")
                }
            }.show()
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Reboot Flipper?")
            .setMessage("This will restart the Flipper Zero.")
            .setPositiveButton("Reboot") { _, _ ->
                sendCommand("power reboot")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================
    // USB CONNECTION
    // ==================

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

    private fun scanAndConnect() {
        log("Scanning USB...")

        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            log("No USB serial devices")
            manager.deviceList.forEach { (_, device) ->
                log("  VID=${String.format("0x%04X", device.vendorId)} PID=${String.format("0x%04X", device.productId)}")
            }
            return
        }

        val driver = drivers.find {
            it.device.vendorId == FLIPPER_VID && it.device.productId == FLIPPER_PID
        } ?: drivers[0]

        val device = driver.device
        log("Found: ${device.productName ?: "Serial Device"}")

        if (manager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            log("Requesting permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, pi)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        log("Connecting...")
        try {
            val manager = usbManager ?: throw Exception("USB Manager null")
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw Exception("No driver")

            usbConnection = manager.openDevice(device) ?: throw Exception("Cannot open")
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true

            ioManager = SerialInputOutputManager(usbSerialPort, this)
            ioManager?.start()

            connected = true
            updateUI()

            mainHandler.postDelayed({
                usbSerialPort?.write("\r\n".toByteArray(), WRITE_TIMEOUT)
            }, 100)

            log("Connected! Baud: $BAUD_RATE")

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
        } catch (e: Exception) { }
        usbSerialPort = null

        try { usbConnection?.close() } catch (e: Exception) { }
        usbConnection = null

        updateUI()
        log("Disconnected")
    }

    // ==================
    // SERIAL I/O
    // ==================

    override fun onNewData(data: ByteArray) {
        val text = String(data)
        Log.d(TAG, "RX[${data.size}]: $text")

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
            log("Not connected!")
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

    private fun sendCtrlC() {
        if (!connected) return
        try {
            usbSerialPort?.write(byteArrayOf(0x03), WRITE_TIMEOUT)
            log("[STOP]")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    private fun clearTerminal() {
        outputText.text = ""
    }

    // ==================
    // UI
    // ==================

    private fun updateUI() {
        btnConnect.text = if (connected) "DISCONNECT" else "CONNECT"
        btnConnect.setBackgroundColor(getColor(if (connected) R.color.red else R.color.blue))

        statusChip.text = if (connected) "ONLINE" else "OFFLINE"
        statusChip.setChipBackgroundColorResource(if (connected) R.color.chip_connected else R.color.chip_offline)

        deviceInfo.text = if (connected) "Flipper Zero @ $BAUD_RATE baud" else "No device connected"

        val toolButtons = listOf(btnRfid, btnNfc, btnSubghz, btnIr, btnIbutton, btnGpio, btnBadusb, btnStorage, btnInfo, btnReboot)
        toolButtons.forEach { it.isEnabled = connected }

        btnStop.isEnabled = connected
        btnSend.isEnabled = connected
        inputField.isEnabled = connected
    }

    private fun log(msg: String) {
        mainHandler.post {
            outputText.append("$msg\n")
            val lines = outputText.text.toString().lines()
            if (lines.size > 200) {
                outputText.text = lines.takeLast(200).joinToString("\n")
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
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { }
    }
}
