package com.android.pen15

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
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
import com.google.android.material.chip.Chip
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.UUID

/**
 * PEN15 v97 - Flipper Zero + ESP32 Controller
 * All CLI commands verified for Unleashed firmware
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
    private var connectionType = ConnectionType.NONE
    private var connected = false
    private var autoReconnect = true
    private var reconnectAttempts = 0

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
    private lateinit var btnWifi: MaterialButton  // ESP32/Marauder

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

        log("=== PEN15 v97 ===")
        log("Flipper Zero Controller")
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

        // ESP32/Marauder WiFi
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

        // Find matching device
        val driver = if (filterVid != null) {
            drivers.find { it.device.vendorId == filterVid }
        } else {
            // Priority: Flipper > ESP32
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

            // Determine device type
            connectionType = when {
                device.vendorId == FLIPPER_VID -> ConnectionType.USB_FLIPPER
                device.vendorId == ESP32_CP210X_VID || device.vendorId == ESP32_CH340_VID -> ConnectionType.USB_ESP32
                else -> ConnectionType.USB_FLIPPER
            }

            connected = true
            reconnectAttempts = 0
            updateUI()

            mainHandler.postDelayed({
                usbSerialPort?.write("\r\n".toByteArray(), WRITE_TIMEOUT)
            }, 100)

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

        // Stop scan after 10 seconds
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

            // Enable notifications on RX
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

        // USB cleanup
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

        // BLE cleanup
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

                // Detect sub-shell prompts and auto-exit
                if (clean.contains("[nfc]>") || clean.contains("[rfid]>") ||
                    clean.contains("[subghz]>") || clean.contains("[ir]>")) {
                    log("[!] Sub-shell detected - sending exit")
                    mainHandler.postDelayed({ sendRawCommand("exit") }, 200)
                }
            }
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

        try {
            when (connectionType) {
                ConnectionType.USB_FLIPPER, ConnectionType.USB_ESP32 -> {
                    usbSerialPort?.write("$cmd\r".toByteArray(), WRITE_TIMEOUT)
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
        commandHistory.remove(cmd) // Remove duplicate
        commandHistory.add(0, cmd) // Add to front
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
        val options = arrayOf("Read Card", "List Saved", "Help")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("RFID (125kHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("rfid read")
                    1 -> sendCommand("storage list /ext/lfrfid")
                    2 -> sendCommand("rfid")
                }
            }.show()
    }

    private fun showNfcMenu() {
        // NFC CLI is limited - most functions require Flipper screen
        val options = arrayOf("Detect Card", "Field On", "Field Off", "List Saved", "Help")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("NFC (13.56MHz)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("nfc detect")
                    1 -> sendCommand("nfc field on")
                    2 -> sendCommand("nfc field off")
                    3 -> sendCommand("storage list /ext/nfc")
                    4 -> sendCommand("nfc")
                }
            }.show()
    }

    private fun showSubghzMenu() {
        val options = arrayOf("RX 433.92MHz", "RX 315MHz", "RX 868MHz", "RX 915MHz", "List Saved", "Help")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Sub-GHz")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("subghz rx 433920000 0")
                    1 -> sendCommand("subghz rx 315000000 0")
                    2 -> sendCommand("subghz rx 868350000 0")
                    3 -> sendCommand("subghz rx 915000000 0")
                    4 -> sendCommand("storage list /ext/subghz")
                    5 -> sendCommand("subghz")
                }
            }.show()
    }

    private fun showFrequencyPicker() {
        val freqNames = subghzFrequencies.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Select Frequency")
            .setItems(freqNames) { _, which ->
                // Device 0 = internal CC1101, 1 = external
                sendCommand("subghz rx ${subghzFrequencies[which].second} 0")
            }.show()
    }

    private fun showIrMenu() {
        val options = arrayOf("Receive Signal", "List Saved", "Help")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Infrared")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("ir rx")
                    1 -> sendCommand("storage list /ext/infrared")
                    2 -> sendCommand("ir")
                }
            }.show()
    }

    private fun showGpioMenu() {
        val options = arrayOf("5V Power ON", "5V Power OFF", "3.3V Power ON", "3.3V Power OFF", "Power Info", "GPIO Help")
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("GPIO / Power")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendCommand("power 5v 1")
                    1 -> sendCommand("power 5v 0")
                    2 -> sendCommand("power 3v3 1")
                    3 -> sendCommand("power 3v3 0")
                    4 -> sendCommand("power")
                    5 -> sendCommand("gpio")
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
    // ESP32/MARAUDER WIFI MENU
    // ==================

    private fun showWifiMenu() {
        if (connectionType == ConnectionType.USB_ESP32) {
            // Direct ESP32/Marauder commands
            val options = arrayOf("Scan APs", "Scan Stations", "Sniff PMKID", "Sniff Beacon", "Deauth All", "Stop Scan", "Help")
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("ESP32 Marauder")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> sendCommand("scanap")
                        1 -> sendCommand("scansta")
                        2 -> sendCommand("sniffpmkid")
                        3 -> sendCommand("sniffbeacon")
                        4 -> sendCommand("attack -t deauth")
                        5 -> sendCommand("stopscan")
                        6 -> sendCommand("help")
                    }
                }.show()
        } else {
            // Flipper - show info about WiFi board
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("WiFi / ESP32")
                .setMessage("WiFi requires ESP32 board.\n\nConnect ESP32/Marauder directly via USB, or use WiFi Devboard on Flipper GPIO.\n\nFor GPIO boards, use the Flipper screen to access WiFi features.")
                .setPositiveButton("OK", null)
                .setNeutralButton("List WiFi Files") { _, _ ->
                    sendCommand("storage list /ext/apps_data/marauder")
                }
                .show()
        }
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
        disconnect()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { }
    }
}
