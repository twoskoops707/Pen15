package com.android.pen15

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.UUID

/**
 * PEN15 v106 - Flipper Zero + AWOK Mini V3 Pentest Sidekick
 * Result parsing, AWOK/Marauder menu, cleaned dead code
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "Pen15"
        private const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"

        // Flipper Zero USB
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740

        // ESP32 USB (common chips)
        private const val ESP32_CP210X_VID = 0x10C4
        private const val ESP32_CP210X_PID = 0xEA60
        private const val ESP32_CH340_VID = 0x1A86
        private const val ESP32_CH340_PID = 0x7523

        // Serial settings
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT = 200

        // Flipper BLE
        private val FLIPPER_SERVICE_UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        private val FLIPPER_RX_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        private val FLIPPER_TX_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")

        private const val PREFS_NAME = "pen15_prefs"
        private const val PREF_COMMAND_HISTORY = "command_history"
        private const val MAX_HISTORY = 50
        private const val RECONNECT_DELAY = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    // Connection state
    private enum class ConnectionType { NONE, USB_FLIPPER, USB_ESP32, BLE }
    private enum class CliState { IDLE, WAKING, READY, SUBSHELL, BUSY }
    private var connectionType = ConnectionType.NONE
    private var cliState = CliState.IDLE
    private var connected = false
    private var autoReconnect = true
    private var reconnectAttempts = 0
    private val commandQueue = mutableListOf<String>()

    // Track last command for result parsing
    private var lastCommand = ""
    private val outputBuffer = StringBuilder()
    private var bufferTimeoutRunnable: Runnable? = null

    // UI
    private lateinit var statusChip: Chip
    private lateinit var deviceInfo: TextView
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnHistory: MaterialButton

    // Result card UI
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultTitle: TextView
    private lateinit var resultFields: TextView
    private lateinit var resultActions: LinearLayout
    private lateinit var btnDismissResult: MaterialButton
    private lateinit var btnResultSave: MaterialButton
    private lateinit var btnResultCopy: MaterialButton

    // Tool buttons
    private lateinit var btnRfid: MaterialButton
    private lateinit var btnNfc: MaterialButton
    private lateinit var btnSubghz: MaterialButton
    private lateinit var btnIr: MaterialButton
    private lateinit var btnIbutton: MaterialButton
    private lateinit var btnGpio: MaterialButton
    private lateinit var btnBadusb: MaterialButton
    private lateinit var btnStorage: MaterialButton
    private lateinit var btnInfo: MaterialButton
    private lateinit var btnWifi: MaterialButton

    // USB
    private var usbManager: UsbManager? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    // Command history
    private lateinit var prefs: SharedPreferences
    private val commandHistory = mutableListOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // SubGHz frequencies
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
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectUSB(it) }
                    } else {
                        log("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB detached")
                    disconnect()
                    if (autoReconnect) scheduleReconnect()
                }
            }
        }
    }

    // ==================
    // PARSED RESULT DATA
    // ==================

    data class ParsedResult(
        val title: String,
        val fields: Map<String, String>,
        val hasActions: Boolean = false,
        val rawData: String = ""
    )

    // ==================
    // LIFECYCLE
    // ==================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_v3)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadCommandHistory()

        initViews()
        setupListeners()
        registerUsbReceiver()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        log("=== PEN15 v106 ===")
        log("Flipper Zero + AWOK Controller")
        log("Tap CONNECT to start")
    }

    private fun initViews() {
        statusChip = findViewById(R.id.statusChip)
        deviceInfo = findViewById(R.id.deviceInfo)
        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        btnSend = findViewById(R.id.btnSend)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)
        btnConnect = findViewById(R.id.btnConnect)
        btnHistory = findViewById(R.id.btnHistory)

        // Result card
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultFields = findViewById(R.id.resultFields)
        resultActions = findViewById(R.id.resultActions)
        btnDismissResult = findViewById(R.id.btnDismissResult)
        btnResultSave = findViewById(R.id.btnResultSave)
        btnResultCopy = findViewById(R.id.btnResultCopy)

        // Tool buttons
        btnRfid = findViewById(R.id.btnRfid)
        btnNfc = findViewById(R.id.btnNfc)
        btnSubghz = findViewById(R.id.btnSubghz)
        btnIr = findViewById(R.id.btnIr)
        btnIbutton = findViewById(R.id.btnIbutton)
        btnGpio = findViewById(R.id.btnGpio)
        btnBadusb = findViewById(R.id.btnBadusb)
        btnStorage = findViewById(R.id.btnStorage)
        btnInfo = findViewById(R.id.btnInfo)
        btnWifi = findViewById(R.id.btnWifi)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (connected) disconnect() else showConnectionMenu()
        }

        btnSend.setOnClickListener {
            val cmd = inputField.text.toString().trim()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                addToHistory(cmd)
                inputField.text.clear()
            }
        }

        btnStop.setOnClickListener { sendCtrlC() }
        btnClear.setOnClickListener { clearTerminal() }
        btnHistory.setOnClickListener { showHistoryMenu() }

        // Result card actions
        btnDismissResult.setOnClickListener { hideResultCard() }
        btnResultCopy.setOnClickListener { copyResultToClipboard() }
        btnResultSave.setOnClickListener { log("[Save] Not yet implemented — coming in v107") }

        // Flipper tools
        btnRfid.setOnClickListener { showRfidMenu() }
        btnNfc.setOnClickListener { showNfcMenu() }
        btnSubghz.setOnClickListener { showSubghzMenu() }
        btnIr.setOnClickListener { showIrMenu() }
        btnIbutton.setOnClickListener { sendCommand("ikey read") }
        btnGpio.setOnClickListener { showGpioMenu() }
        btnBadusb.setOnClickListener { showBadusbMenu() }
        btnStorage.setOnClickListener { showStorageMenu() }
        btnInfo.setOnClickListener { sendCommand("device_info") }

        // ESP32/Marauder WiFi / AWOK
        btnWifi.setOnClickListener { showWifiMenu() }
    }

    // ==================
    // CONNECTION MENU
    // ==================

    private fun showConnectionMenu() {
        val options = arrayOf("USB (Auto-detect)", "USB Flipper Only", "USB ESP32 Only", "Bluetooth (Flipper)")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Connect via")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> scanAndConnectUSB(null)
                    1 -> scanAndConnectUSB(FLIPPER_VID)
                    2 -> scanAndConnectUSB(ESP32_CP210X_VID)
                    3 -> connectBLE()
                }
            }.show()
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

    private fun scanAndConnectUSB(filterVid: Int?) {
        log("Scanning USB...")
        reconnectAttempts = 0

        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            log("No USB serial devices found")
            manager.deviceList.forEach { (_, device) ->
                log("  Raw: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            }
            return
        }

        val driver = if (filterVid != null) {
            drivers.find { it.device.vendorId == filterVid }
        } else {
            drivers.find { it.device.vendorId == FLIPPER_VID && it.device.productId == FLIPPER_PID }
                ?: drivers.find { it.device.vendorId == ESP32_CP210X_VID || it.device.vendorId == ESP32_CH340_VID }
                ?: drivers[0]
        }

        if (driver == null) {
            log("No matching device found")
            return
        }

        val device = driver.device
        log("Found: ${device.productName ?: "USB Device"}")

        if (manager.hasPermission(device)) {
            connectUSB(device)
        } else {
            log("Requesting permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, pi)
        }
    }

    private fun connectUSB(device: UsbDevice) {
        log("Connecting USB...")
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

            connectionType = when {
                device.vendorId == FLIPPER_VID -> ConnectionType.USB_FLIPPER
                device.vendorId == ESP32_CP210X_VID || device.vendorId == ESP32_CH340_VID -> ConnectionType.USB_ESP32
                else -> ConnectionType.USB_FLIPPER
            }

            connected = true
            reconnectAttempts = 0
            cliState = CliState.WAKING
            commandQueue.clear()
            updateUI()

            mainHandler.postDelayed({
                log("Waking CLI...")
                usbSerialPort?.write(byteArrayOf(0x03), WRITE_TIMEOUT)
            }, 500)
            mainHandler.postDelayed({
                usbSerialPort?.write("\r".toByteArray(), WRITE_TIMEOUT)
            }, 800)
            mainHandler.postDelayed({
                usbSerialPort?.write("\r".toByteArray(), WRITE_TIMEOUT)
            }, 1100)

            val deviceName = if (connectionType == ConnectionType.USB_ESP32) "ESP32/Marauder" else "Flipper Zero"
            log("Connected: $deviceName @ $BAUD_RATE baud")

        } catch (e: Exception) {
            Log.e(TAG, "USB connection error", e)
            log("ERROR: ${e.message}")
            disconnect()
        }
    }

    // ==================
    // BLUETOOTH CONNECTION
    // ==================

    @SuppressLint("MissingPermission")
    private fun connectBLE() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        log("Scanning for Flipper BLE...")

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            log("BLE not available")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FLIPPER_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(scanFilter), scanSettings, bleScanCallback)

        mainHandler.postDelayed({
            bleScanner?.stopScan(bleScanCallback)
            if (!connected) {
                log("No Flipper found via BLE")
            }
        }, 10000)
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            bleScanner?.stopScan(this)
            log("Found Flipper: ${result.device.name ?: result.device.address}")
            connectGatt(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        log("Connecting BLE...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("BLE connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mainHandler.post {
                        log("BLE disconnected")
                        disconnect()
                        if (autoReconnect) scheduleReconnect()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed")
                return
            }

            val service = gatt.getService(FLIPPER_SERVICE_UUID)
            if (service == null) {
                log("Flipper service not found")
                return
            }

            txCharacteristic = service.getCharacteristic(FLIPPER_TX_UUID)
            rxCharacteristic = service.getCharacteristic(FLIPPER_RX_UUID)

            rxCharacteristic?.let { rx ->
                gatt.setCharacteristicNotification(rx, true)
                val descriptor = rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }

            mainHandler.post {
                connectionType = ConnectionType.BLE
                connected = true
                reconnectAttempts = 0
                updateUI()
                log("BLE ready! Flipper connected")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == FLIPPER_RX_UUID) {
                val data = characteristic.value
                mainHandler.post {
                    val text = String(data).replace("\r\n", "\n").replace("\r", "")
                    if (text.isNotBlank()) {
                        outputText.append(text)
                        scrollToBottom()
                    }
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 100)
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    // ==================
    // AUTO-RECONNECT
    // ==================

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log("Max reconnect attempts reached")
            return
        }

        reconnectAttempts++
        log("Reconnecting in ${RECONNECT_DELAY/1000}s (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

        mainHandler.postDelayed({
            if (!connected && autoReconnect) {
                scanAndConnectUSB(null)
            }
        }, RECONNECT_DELAY)
    }

    // ==================
    // DISCONNECT
    // ==================

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        connected = false
        connectionType = ConnectionType.NONE

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

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) { }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null

        updateUI()
        log("Disconnected")
    }

    // ==================
    // SERIAL I/O + RESULT BUFFERING
    // ==================

    override fun onNewData(data: ByteArray) {
        val text = String(data)
        Log.d(TAG, "RX[${data.size}]: $text")

        mainHandler.post {
            val clean = text.replace("\r\n", "\n").replace("\r", "")
            if (clean.isNotBlank()) {
                outputText.append(clean)
                scrollToBottom()

                // Buffer output for result parsing
                outputBuffer.append(clean)
                resetBufferTimeout()

                // Detect CLI state from prompts
                if (clean.contains("[nfc]>") || clean.contains("[subghz]>") ||
                    clean.contains("[ir]>") || clean.contains("[lfrfid]>") ||
                    clean.contains("[rfid]>")) {
                    cliState = CliState.SUBSHELL
                    log("[!] Sub-shell detected, exiting...")
                    mainHandler.postDelayed({
                        try {
                            usbSerialPort?.write("exit\r".toByteArray(), WRITE_TIMEOUT)
                        } catch (e: Exception) {}
                    }, 100)
                }
                else if (clean.contains(">: ") || clean.endsWith(">:") ||
                         clean.contains(">:\n") || clean.contains(">:\r")) {
                    if (cliState != CliState.READY) {
                        cliState = CliState.READY
                        log("[CLI Ready]")
                    }
                    // Command finished — try to parse buffered output
                    tryParseResult()
                    processCommandQueue()
                }
            }
        }
    }

    private fun resetBufferTimeout() {
        bufferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferTimeoutRunnable = Runnable { tryParseResult() }
        mainHandler.postDelayed(bufferTimeoutRunnable!!, 3000)
    }

    private fun tryParseResult() {
        bufferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val raw = outputBuffer.toString()
        outputBuffer.clear()
        if (raw.isBlank() || lastCommand.isBlank()) return

        val result = parseResult(lastCommand, raw)
        if (result != null) {
            showResultCard(result)
        }
    }

    private fun processCommandQueue() {
        if (commandQueue.isNotEmpty() && cliState == CliState.READY) {
            val cmd = commandQueue.removeAt(0)
            log("[Queue] Sending: $cmd")
            sendCommandDirect(cmd)
        }
    }

    private fun sendCommandDirect(cmd: String) {
        try {
            usbSerialPort?.write("$cmd\r".toByteArray(), WRITE_TIMEOUT)
            cliState = CliState.BUSY
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            log("Send error: ${e.message}")
        }
    }

    private fun sendRawCommand(cmd: String) {
        try {
            usbSerialPort?.write("$cmd\r".toByteArray(), WRITE_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw error", e)
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "IO Error", e)
        mainHandler.post {
            log("IO Error: ${e.message}")
            disconnect()
            if (autoReconnect) scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(cmd: String) {
        if (!connected) {
            log("Not connected!")
            return
        }
        log("> $cmd")
        lastCommand = cmd
        outputBuffer.clear()

        try {
            when (connectionType) {
                ConnectionType.USB_FLIPPER, ConnectionType.USB_ESP32 -> {
                    if (cliState == CliState.READY) {
                        sendCommandDirect(cmd)
                    } else {
                        commandQueue.add(cmd)
                        log("[Queued - CLI not ready yet]")
                        usbSerialPort?.write("\r".toByteArray(), WRITE_TIMEOUT)
                    }
                }
                ConnectionType.BLE -> {
                    txCharacteristic?.let { tx ->
                        tx.value = "$cmd\r".toByteArray()
                        bluetoothGatt?.writeCharacteristic(tx)
                    }
                }
                else -> log("No connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            log("Send error: ${e.message}")
        }
    }

    private fun sendCtrlC() {
        if (!connected) return
        try {
            when (connectionType) {
                ConnectionType.USB_FLIPPER, ConnectionType.USB_ESP32 -> {
                    usbSerialPort?.write(byteArrayOf(0x03), WRITE_TIMEOUT)
                }
                ConnectionType.BLE -> {
                    txCharacteristic?.let { tx ->
                        tx.value = byteArrayOf(0x03)
                        bluetoothGatt?.writeCharacteristic(tx)
                    }
                }
                else -> {}
            }
            log("[STOP]")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    // ==================
    // RESULT PARSING
    // ==================

    private fun parseResult(command: String, rawOutput: String): ParsedResult? {
        return when {
            command.startsWith("lfrfid") || command.startsWith("rfid") -> parseRfidResult(rawOutput)
            command.startsWith("subghz") -> parseSubghzResult(rawOutput)
            command.startsWith("ir ") -> parseIrResult(rawOutput)
            command.startsWith("ikey") -> parseIkeyResult(rawOutput)
            command.startsWith("storage list") -> parseStorageList(rawOutput)
            command.startsWith("storage info") -> parseStorageInfo(rawOutput)
            command == "device_info" -> parseDeviceInfo(rawOutput)
            command.startsWith("power") -> parsePowerResult(rawOutput)
            // AWOK / Marauder results
            command == "scanap" -> parseMarauderScanAp(rawOutput)
            command == "list -a" || command == "list" -> parseMarauderListAp(rawOutput)
            command.startsWith("attack") -> parseMarauderAttack(rawOutput)
            command.startsWith("sniff") -> parseMarauderSniff(rawOutput)
            else -> null
        }
    }

    private fun parseRfidResult(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        // Look for protocol and data patterns
        val protoMatch = Regex("Protocol:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(raw)
        val dataMatch = Regex("Data:\\s*([0-9A-Fa-f\\s]+)", RegexOption.IGNORE_CASE).find(raw)
        val typeMatch = Regex("Type:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)

        if (protoMatch != null) fields["Protocol"] = protoMatch.groupValues[1]
        if (dataMatch != null) fields["Data"] = dataMatch.groupValues[1].trim()
        if (typeMatch != null) fields["Type"] = typeMatch.groupValues[1].trim()

        if (fields.isEmpty()) {
            if (raw.contains("Reading", ignoreCase = true)) {
                return ParsedResult("RFID Reading...", mapOf("Status" to "Waiting for card..."), rawData = raw)
            }
            return null
        }
        return ParsedResult("RFID Card Detected", fields, hasActions = true, rawData = raw)
    }

    private fun parseSubghzResult(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val freqMatch = Regex("Frequency:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)
        val protoMatch = Regex("Protocol:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(raw)
        val keyMatch = Regex("Key:\\s*([0-9A-Fa-f\\s]+)", RegexOption.IGNORE_CASE).find(raw)
        val bitMatch = Regex("Bit:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)

        if (freqMatch != null) {
            val freq = freqMatch.groupValues[1].toLongOrNull()
            fields["Frequency"] = if (freq != null) "${freq / 1_000_000.0} MHz" else freqMatch.groupValues[1]
        }
        if (protoMatch != null) fields["Protocol"] = protoMatch.groupValues[1]
        if (keyMatch != null) fields["Key"] = keyMatch.groupValues[1].trim()
        if (bitMatch != null) fields["Bits"] = bitMatch.groupValues[1]

        if (fields.isEmpty()) return null
        return ParsedResult("Sub-GHz Signal Captured", fields, hasActions = true, rawData = raw)
    }

    private fun parseIrResult(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val protoMatch = Regex("Protocol:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(raw)
        val addrMatch = Regex("Address:\\s*([0-9A-Fa-fxX]+)", RegexOption.IGNORE_CASE).find(raw)
        val cmdMatch = Regex("Command:\\s*([0-9A-Fa-fxX]+)", RegexOption.IGNORE_CASE).find(raw)

        if (protoMatch != null) fields["Protocol"] = protoMatch.groupValues[1]
        if (addrMatch != null) fields["Address"] = addrMatch.groupValues[1]
        if (cmdMatch != null) fields["Command"] = cmdMatch.groupValues[1]

        if (fields.isEmpty()) return null
        return ParsedResult("IR Signal Received", fields, hasActions = true, rawData = raw)
    }

    private fun parseIkeyResult(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val typeMatch = Regex("Type:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        val idMatch = Regex("ID:\\s*([0-9A-Fa-f\\s:]+)", RegexOption.IGNORE_CASE).find(raw)

        if (typeMatch != null) fields["Type"] = typeMatch.groupValues[1].trim()
        if (idMatch != null) fields["ID"] = idMatch.groupValues[1].trim()

        if (fields.isEmpty()) return null
        return ParsedResult("iButton Key Read", fields, hasActions = true, rawData = raw)
    }

    private fun parseStorageList(raw: String): ParsedResult? {
        val lines = raw.lines().filter { it.contains("[D]") || it.contains("[F]") || it.contains("Storage") }
        if (lines.isEmpty()) return null

        val dirs = raw.lines().count { it.contains("[D]") }
        val files = raw.lines().count { it.contains("[F]") }
        val fields = mutableMapOf<String, String>()
        fields["Directories"] = dirs.toString()
        fields["Files"] = files.toString()

        // Show first few entries
        val entries = raw.lines()
            .filter { it.trimStart().startsWith("[D]") || it.trimStart().startsWith("[F]") }
            .take(8)
            .joinToString("\n") { it.trim() }
        if (entries.isNotEmpty()) fields["Contents"] = "\n$entries"

        return ParsedResult("Storage Listing", fields, rawData = raw)
    }

    private fun parseStorageInfo(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val totalMatch = Regex("Total:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)
        val freeMatch = Regex("Free:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)
        if (totalMatch != null) fields["Total"] = "${totalMatch.groupValues[1].toLongOrNull()?.div(1024) ?: "?"} KB"
        if (freeMatch != null) fields["Free"] = "${freeMatch.groupValues[1].toLongOrNull()?.div(1024) ?: "?"} KB"
        if (fields.isEmpty()) return null
        return ParsedResult("Storage Info", fields, rawData = raw)
    }

    private fun parseDeviceInfo(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val hwMatch = Regex("hardware\\.model\\s*:\\s*(.+)").find(raw)
        val fwMatch = Regex("firmware\\.version\\s*:\\s*(.+)").find(raw)
        val buildMatch = Regex("firmware\\.build\\.date\\s*:\\s*(.+)").find(raw)
        val radioMatch = Regex("radio\\.stack\\.major\\s*:\\s*(.+)").find(raw)

        if (hwMatch != null) fields["Model"] = hwMatch.groupValues[1].trim()
        if (fwMatch != null) fields["Firmware"] = fwMatch.groupValues[1].trim()
        if (buildMatch != null) fields["Build Date"] = buildMatch.groupValues[1].trim()
        if (radioMatch != null) fields["Radio Stack"] = radioMatch.groupValues[1].trim()

        if (fields.isEmpty()) return null
        return ParsedResult("Device Info", fields, rawData = raw)
    }

    private fun parsePowerResult(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        val voltMatch = Regex("Voltage:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        val chargeMatch = Regex("Charge:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        val tempMatch = Regex("Temperature:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        val currentMatch = Regex("Current:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)

        if (voltMatch != null) fields["Voltage"] = voltMatch.groupValues[1].trim()
        if (chargeMatch != null) fields["Charge"] = chargeMatch.groupValues[1].trim()
        if (tempMatch != null) fields["Temperature"] = tempMatch.groupValues[1].trim()
        if (currentMatch != null) fields["Current"] = currentMatch.groupValues[1].trim()

        // Also handle "5v on/off" confirmations
        if (fields.isEmpty() && (raw.contains("5v", ignoreCase = true) || raw.contains("otg", ignoreCase = true))) {
            return ParsedResult("Power", mapOf("Status" to raw.trim().lines().last().trim()), rawData = raw)
        }
        if (fields.isEmpty()) return null
        return ParsedResult("Power Info", fields, rawData = raw)
    }

    // Marauder result parsers
    private fun parseMarauderScanAp(raw: String): ParsedResult? {
        val apCount = Regex("(\\d+)\\s+APs?\\s+found", RegexOption.IGNORE_CASE).find(raw)
        val fields = mutableMapOf<String, String>()
        if (apCount != null) {
            fields["APs Found"] = apCount.groupValues[1]
        }
        // Look for scan lines like "0: SSID (CH:6) RSSI:-45"
        val aps = raw.lines().filter { it.matches(Regex("^\\s*\\d+:.*")) }.take(10)
        if (aps.isNotEmpty()) {
            fields["Networks"] = "\n" + aps.joinToString("\n") { it.trim() }
        }
        if (fields.isEmpty()) {
            if (raw.contains("scan", ignoreCase = true)) {
                return ParsedResult("AP Scan", mapOf("Status" to "Scanning..."), rawData = raw)
            }
            return null
        }
        return ParsedResult("AP Scan Complete", fields, rawData = raw)
    }

    private fun parseMarauderListAp(raw: String): ParsedResult? {
        val aps = raw.lines().filter { it.matches(Regex("^\\s*\\d+:.*")) || it.contains("SSID", ignoreCase = true) }
        if (aps.isEmpty()) return null
        val fields = mutableMapOf<String, String>()
        fields["APs"] = "\n" + aps.take(15).joinToString("\n") { it.trim() }
        return ParsedResult("AP List", fields, rawData = raw)
    }

    private fun parseMarauderAttack(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        if (raw.contains("deauth", ignoreCase = true)) fields["Type"] = "Deauth"
        if (raw.contains("beacon", ignoreCase = true)) fields["Type"] = "Beacon Spam"
        if (raw.contains("probe", ignoreCase = true)) fields["Type"] = "Probe Spam"
        if (raw.contains("started", ignoreCase = true)) fields["Status"] = "Running"
        if (raw.contains("stopped", ignoreCase = true)) fields["Status"] = "Stopped"
        val pktMatch = Regex("(\\d+)\\s+packets?", RegexOption.IGNORE_CASE).find(raw)
        if (pktMatch != null) fields["Packets"] = pktMatch.groupValues[1]
        if (fields.isEmpty()) return null
        return ParsedResult("Attack Status", fields, rawData = raw)
    }

    private fun parseMarauderSniff(raw: String): ParsedResult? {
        val fields = mutableMapOf<String, String>()
        if (raw.contains("pmkid", ignoreCase = true)) fields["Type"] = "PMKID Sniff"
        if (raw.contains("beacon", ignoreCase = true)) fields["Type"] = "Beacon Sniff"
        if (raw.contains("deauth", ignoreCase = true)) fields["Type"] = "Deauth Sniff"
        val capMatch = Regex("(\\d+)\\s+captured", RegexOption.IGNORE_CASE).find(raw)
        if (capMatch != null) fields["Captured"] = capMatch.groupValues[1]
        if (raw.contains("started", ignoreCase = true)) fields["Status"] = "Running"
        if (raw.contains("stopped", ignoreCase = true)) fields["Status"] = "Stopped"
        if (fields.isEmpty()) return null
        return ParsedResult("Sniff Status", fields, rawData = raw)
    }

    // ==================
    // RESULT CARD UI
    // ==================

    private fun showResultCard(result: ParsedResult) {
        resultTitle.text = result.title
        val sb = StringBuilder()
        for ((key, value) in result.fields) {
            if (value.startsWith("\n")) {
                sb.append("$key:$value\n")
            } else {
                sb.append("$key: $value\n")
            }
        }
        resultFields.text = sb.toString().trimEnd()
        resultActions.visibility = if (result.hasActions) View.VISIBLE else View.GONE
        resultCard.visibility = View.VISIBLE

        // Store raw data for copy
        resultCard.tag = result.rawData
    }

    private fun hideResultCard() {
        resultCard.visibility = View.GONE
    }

    private fun copyResultToClipboard() {
        val text = resultFields.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PEN15 Result", text))
        log("[Copied to clipboard]")
    }

    // ==================
    // COMMAND HISTORY
    // ==================

    private fun loadCommandHistory() {
        val saved = prefs.getString(PREF_COMMAND_HISTORY, "") ?: ""
        commandHistory.clear()
        if (saved.isNotEmpty()) {
            commandHistory.addAll(saved.split("\n").filter { it.isNotBlank() })
        }
    }

    private fun saveCommandHistory() {
        prefs.edit().putString(PREF_COMMAND_HISTORY, commandHistory.joinToString("\n")).apply()
    }

    private fun addToHistory(cmd: String) {
        commandHistory.remove(cmd)
        commandHistory.add(0, cmd)
        if (commandHistory.size > MAX_HISTORY) {
            commandHistory.removeAt(commandHistory.size - 1)
        }
        saveCommandHistory()
    }

    private fun showHistoryMenu() {
        if (commandHistory.isEmpty()) {
            log("No command history")
            return
        }

        val items = commandHistory.take(20).toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Command History")
            .setItems(items) { _, which ->
                inputField.setText(items[which])
            }
            .setNegativeButton("Clear All") { _, _ ->
                commandHistory.clear()
                saveCommandHistory()
                log("History cleared")
            }
            .show()
    }

    // ==================
    // TOOL MENUS
    // ==================

    private fun showRfidMenu() {
        val options = arrayOf("Read Card (hold near)", "List Saved", "Emulate Last")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("RFID (125kHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("lfrfid read")
                    1 -> sendCommand("storage list /ext/lfrfid")
                    2 -> sendCommand("lfrfid emulate")
                }
            }.show()
    }

    private fun showNfcMenu() {
        val options = arrayOf("Field ON (detect)", "Field OFF", "List Saved")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("NFC (13.56MHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("nfc field on")
                    1 -> sendCommand("nfc field off")
                    2 -> sendCommand("storage list /ext/nfc")
                }
            }.show()
    }

    private fun showSubghzMenu() {
        val options = arrayOf("RX 433.92MHz", "RX 315MHz", "RX 868MHz", "RX 915MHz", "List Saved", "TX (use Flipper)")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Sub-GHz")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("subghz rx 433920000")
                    1 -> sendCommand("subghz rx 315000000")
                    2 -> sendCommand("subghz rx 868350000")
                    3 -> sendCommand("subghz rx 915000000")
                    4 -> sendCommand("storage list /ext/subghz")
                    5 -> log("TX requires Flipper screen - use SubGHz app")
                }
            }.show()
    }

    private fun showFrequencyPicker() {
        val freqNames = subghzFrequencies.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Select Frequency")
            .setItems(freqNames) { _, which ->
                sendCommand("subghz rx ${subghzFrequencies[which].second} 0")
            }.show()
    }

    private fun showIrMenu() {
        val options = arrayOf("Receive Signal", "List Saved", "List Universal DBs")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Infrared")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("ir rx")
                    1 -> sendCommand("storage list /ext/infrared")
                    2 -> sendCommand("storage list /ext/infrared/assets")
                }
            }.show()
    }

    private fun showGpioMenu() {
        val options = arrayOf("5V Power ON", "5V Power OFF", "OTG ON", "OTG OFF", "Power Info")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("GPIO / Power")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("power 5v on")
                    1 -> sendCommand("power 5v off")
                    2 -> sendCommand("power otg on")
                    3 -> sendCommand("power otg off")
                    4 -> sendCommand("power info")
                }
            }.show()
    }

    private fun showBadusbMenu() {
        val options = arrayOf("List Payloads", "Demo Script")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("BadUSB")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("storage list /ext/badusb")
                    1 -> sendCommand("storage read /ext/badusb/demo.txt")
                }
            }.show()
    }

    private fun showStorageMenu() {
        val options = arrayOf("SD Root", "RFID", "NFC", "SubGHz", "IR", "Free Space")
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

    // ==================
    // AWOK MINI V3 / ESP32 MARAUDER WIFI MENU
    // ==================

    private fun showWifiMenu() {
        if (connectionType == ConnectionType.USB_ESP32) {
            showMarauderDirectMenu()
        } else if (connectionType == ConnectionType.USB_FLIPPER) {
            showMarauderViaFlipperMenu()
        } else {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("WiFi / AWOK")
                .setMessage("Connect to Flipper or ESP32 first.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showMarauderDirectMenu() {
        val options = arrayOf(
            "Scan APs", "List APs", "Select AP...",
            "Deauth Attack", "Beacon Spam", "Probe Spam",
            "Sniff PMKID", "Sniff Beacons", "Sniff Deauth",
            "BLE Spam All", "Sniff BT",
            "Stop", "Help"
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("ESP32 Marauder (Direct)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("scanap")
                    1 -> sendCommand("list -a")
                    2 -> showSelectApDialog()
                    3 -> sendCommand("attack -t deauth")
                    4 -> sendCommand("attack -t beacon")
                    5 -> sendCommand("attack -t probe")
                    6 -> sendCommand("sniffpmkid")
                    7 -> sendCommand("sniffbeacon")
                    8 -> sendCommand("sniffdeauth")
                    9 -> sendCommand("btspamall")
                    10 -> sendCommand("sniffbt")
                    11 -> sendCommand("stopscan")
                    12 -> sendCommand("help")
                }
            }.show()
    }

    private fun showMarauderViaFlipperMenu() {
        // AWOK/Marauder through Flipper GPIO UART bridge
        // User must have UART Bridge active on Flipper screen (GPIO → UART Bridge)
        val options = arrayOf(
            "Scan APs", "List APs", "Select AP...",
            "Deauth Attack", "Beacon Spam", "Probe Spam",
            "Sniff PMKID", "Sniff Beacons", "Sniff Deauth",
            "BLE Spam All", "Sniff BT",
            "Stop", "Help",
            "--- Flipper WiFi Files ---"
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("AWOK Marauder (via Flipper)")
            .setMessage("Ensure UART Bridge is active on Flipper screen (GPIO → UART Bridge)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("scanap")
                    1 -> sendCommand("list -a")
                    2 -> showSelectApDialog()
                    3 -> sendCommand("attack -t deauth")
                    4 -> sendCommand("attack -t beacon")
                    5 -> sendCommand("attack -t probe")
                    6 -> sendCommand("sniffpmkid")
                    7 -> sendCommand("sniffbeacon")
                    8 -> sendCommand("sniffdeauth")
                    9 -> sendCommand("btspamall")
                    10 -> sendCommand("sniffbt")
                    11 -> sendCommand("stopscan")
                    12 -> sendCommand("help")
                    13 -> sendCommand("storage list /ext/apps_data/marauder")
                }
            }.show()
    }

    private fun showSelectApDialog() {
        val input = EditText(this).apply {
            hint = "AP index (0-based)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFE5E7EB.toInt())
            setHintTextColor(0xFF4A5B78.toInt())
        }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Select AP")
            .setMessage("Run 'Scan APs' first, then enter the AP index:")
            .setView(input)
            .setPositiveButton("Select") { _, _ ->
                val idx = input.text.toString().trim()
                if (idx.isNotEmpty()) {
                    sendCommand("select -a $idx")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================
    // UI
    // ==================

    private fun updateUI() {
        btnConnect.text = if (connected) "DISCONNECT" else "CONNECT"
        btnConnect.setBackgroundColor(getColor(if (connected) R.color.red else R.color.blue))

        val statusText = when (connectionType) {
            ConnectionType.USB_FLIPPER -> "FLIPPER"
            ConnectionType.USB_ESP32 -> "ESP32"
            ConnectionType.BLE -> "BLE"
            else -> "OFFLINE"
        }
        statusChip.text = statusText
        statusChip.setChipBackgroundColorResource(if (connected) R.color.chip_connected else R.color.chip_offline)

        deviceInfo.text = when (connectionType) {
            ConnectionType.USB_FLIPPER -> "Flipper Zero @ $BAUD_RATE baud (USB)"
            ConnectionType.USB_ESP32 -> "ESP32/Marauder @ $BAUD_RATE baud (USB)"
            ConnectionType.BLE -> "Flipper Zero (Bluetooth)"
            else -> "No device connected"
        }

        val toolButtons = listOf(btnRfid, btnNfc, btnSubghz, btnIr, btnIbutton, btnGpio, btnBadusb, btnStorage, btnInfo, btnWifi)
        toolButtons.forEach { it.isEnabled = connected }

        btnStop.isEnabled = connected
        btnSend.isEnabled = connected
        inputField.isEnabled = connected
    }

    private fun clearTerminal() {
        outputText.text = ""
        hideResultCard()
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

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        autoReconnect = false
        bufferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnect()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { }
    }
}
