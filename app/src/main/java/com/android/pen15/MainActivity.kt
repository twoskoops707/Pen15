package com.android.pen15

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pen15 - Flipper Zero Interface
 * Using usb-serial-for-android library with SerialInputOutputManager for stable USB CDC
 * Refs: https://github.com/mik3y/usb-serial-for-android
 *       https://github.com/flipperdevices/Flipper-Android-App
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "Pen15"
        private const val FLIPPER_VID = 0x0483
        private const val FLIPPER_PID = 0x5740

        // Flipper BLE UUIDs (from official app source)
        private val FLIPPER_BLE_SERVICE = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        private val FLIPPER_BLE_RX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        private val FLIPPER_BLE_TX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUEST_PERMISSIONS = 100
    }

    // UI Elements
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnConnect: Button
    private lateinit var btnTest: Button
    private lateinit var btnRFID: Button
    private lateinit var btnSubGHz: Button
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    // USB Serial (using SerialInputOutputManager for proper async handling)
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val usbExecutor = Executors.newSingleThreadExecutor()

    // BLE Connection
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleRxChar: BluetoothCharacteristic? = null
    private var bleTxChar: BluetoothCharacteristic? = null

    // State
    private val isConnected = AtomicBoolean(false)
    private var connectionType = "NONE"

    // Response handling for command/response pattern
    private val responseBuffer = StringBuilder()
    private var waitingForResponse = false
    private var responseCallback: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_simple)

        initViews()
        setupButtons()
        requestPermissions()

        log("=== PEN15 v74 ===")
        log("USB Serial via usb-serial-for-android")
        log("Using SerialInputOutputManager")
        log("")
        log("Connect Flipper via USB-C")
        log("Then tap CONNECT")
    }

    private fun initViews() {
        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        progressBar = findViewById(R.id.progressBar)
        btnConnect = findViewById(R.id.btnConnect)
        btnTest = findViewById(R.id.btnTest)
        btnRFID = findViewById(R.id.btnRFID)
        btnSubGHz = findViewById(R.id.btnSubGHz)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
    }

    private fun setupButtons() {
        btnConnect.setOnClickListener { connect() }
        btnTest.setOnClickListener { sendFlipperCommand("?") }
        btnRFID.setOnClickListener { sendFlipperCommand("rfid read") }
        btnSubGHz.setOnClickListener { sendFlipperCommand("subghz rx 433920000") }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (needed.isNotEmpty())
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    // =====================
    // USB CONNECTION
    // =====================

    private fun connect() {
        if (isConnected.get()) {
            disconnect()
            return
        }

        log("")
        log("--- CONNECTING USB ---")
        showProgress(true)

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { connectUSB() }
            showProgress(false)

            if (success) {
                isConnected.set(true)
                connectionType = "USB"
                updateUI()
                log("Connected! Tap TEST to verify.")
            } else {
                log("Connection failed")
            }
        }
    }

    private fun connectUSB(): Boolean {
        try {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

            if (drivers.isEmpty()) {
                runOnUI { log("No USB serial devices found") }

                // Log raw USB devices for debugging
                manager.deviceList.forEach { (_, device) ->
                    runOnUI {
                        log("Device: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
                    }
                }
                return false
            }

            // Find Flipper Zero or use first device
            var selectedDriver = drivers.firstOrNull {
                it.device.vendorId == FLIPPER_VID && it.device.productId == FLIPPER_PID
            }

            if (selectedDriver == null) {
                selectedDriver = drivers[0]
                runOnUI { log("Using first device (not Flipper)") }
            } else {
                runOnUI { log("Found Flipper Zero!") }
            }

            // Check permission
            if (!manager.hasPermission(selectedDriver.device)) {
                runOnUI { log("ERROR: No USB permission granted") }
                return false
            }

            // Open device
            val connection = manager.openDevice(selectedDriver.device)
            if (connection == null) {
                runOnUI { log("ERROR: Cannot open device") }
                return false
            }

            // Open port
            val port = selectedDriver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // CRITICAL: Set DTR to indicate we're ready (required for CDC-ACM)
            // Ref: https://github.com/mik3y/usb-serial-for-android/wiki/FAQ
            port.dtr = true
            port.rts = true

            runOnUI { log("Port opened: 115200 8N1, DTR=ON") }

            // Start SerialInputOutputManager for async reads
            // This is the CORRECT way to handle USB serial - NOT blocking reads
            usbSerialPort = port
            usbIoManager = SerialInputOutputManager(port, this)
            usbIoManager?.start()

            runOnUI { log("SerialInputOutputManager started") }

            // Send initial newline to wake CLI
            Thread.sleep(100)
            port.write("\r\n".toByteArray(), 500)

            Thread.sleep(200)
            runOnUI { log("SUCCESS!") }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "USB connect error", e)
            runOnUI { log("ERROR: ${e.message}") }
            return false
        }
    }

    // SerialInputOutputManager.Listener implementation
    override fun onNewData(data: ByteArray) {
        val text = String(data)
        Log.d(TAG, "USB RX: ${text.length} bytes")

        mainHandler.post {
            responseBuffer.append(text)

            // Check if we have a complete response (ends with prompt)
            val response = responseBuffer.toString()
            if (response.contains(">:") || response.endsWith(">")) {
                if (waitingForResponse) {
                    waitingForResponse = false
                    responseCallback?.invoke(response)
                    responseCallback = null
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "SerialInputOutputManager error", e)
        mainHandler.post {
            log("USB Error: ${e.message}")
            if (isConnected.get()) {
                log("Connection lost!")
                disconnect()
            }
        }
    }

    // =====================
    // COMMANDS
    // =====================

    private fun sendFlipperCommand(cmd: String) {
        if (!isConnected.get()) {
            log("Not connected!")
            return
        }

        log("")
        log("> $cmd")
        showProgress(true)

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                when (connectionType) {
                    "USB" -> sendUSBCommand(cmd)
                    "BLE" -> sendBLECommand(cmd)
                    else -> "Not connected"
                }
            }

            showProgress(false)

            // Parse and display response
            val cleanResponse = parseFlipperResponse(cmd, response)
            if (cleanResponse.isNotEmpty()) {
                log(cleanResponse)
            } else {
                log("(no response)")
            }
        }
    }

    private fun sendUSBCommand(cmd: String): String {
        val port = usbSerialPort ?: return "USB not connected"

        return try {
            // Clear buffer for new command
            responseBuffer.clear()
            waitingForResponse = true

            // Send command with CR (Flipper expects \r)
            val cmdBytes = "$cmd\r".toByteArray()
            port.write(cmdBytes, 1000)

            // Wait for response with timeout
            val startTime = System.currentTimeMillis()
            var result = ""

            // Simple wait loop - SerialInputOutputManager will populate responseBuffer
            while (System.currentTimeMillis() - startTime < 5000) {
                val currentResponse = responseBuffer.toString()
                if (currentResponse.contains(">:") || currentResponse.contains("\r\n>")) {
                    result = currentResponse
                    break
                }
                Thread.sleep(50)
            }

            waitingForResponse = false

            if (result.isEmpty()) {
                result = responseBuffer.toString() // Get whatever we have
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "USB command error", e)
            "Error: ${e.message}"
        }
    }

    private fun parseFlipperResponse(cmd: String, raw: String): String {
        return raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .filterNot {
                it.trim() == cmd ||
                        it.trim().startsWith(">:") ||
                        it.trim() == ">" ||
                        it.trim().isEmpty()
            }
            .joinToString("\n")
            .trim()
    }

    // =====================
    // BLE CONNECTION (Stub - uses official Flipper BLE UUIDs)
    // =====================

    @SuppressLint("MissingPermission")
    private fun connectBLE() {
        log("")
        log("--- BLE CONNECTION ---")

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            log("ERROR: Bluetooth disabled")
            return
        }

        // Find paired Flipper
        val flipper = adapter.bondedDevices.firstOrNull { it.name?.startsWith("Flipper") == true }
        if (flipper == null) {
            log("ERROR: No paired Flipper found")
            log("Pair in Settings > Bluetooth first")
            return
        }

        log("Connecting to ${flipper.name}...")
        showProgress(true)
        bluetoothGatt = flipper.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUI {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("BLE connected")
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("BLE disconnected")
                        isConnected.set(false)
                        connectionType = "NONE"
                        showProgress(false)
                        updateUI()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUI {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed")
                    showProgress(false)
                    return@runOnUI
                }

                val service = gatt.getService(FLIPPER_BLE_SERVICE)
                if (service == null) {
                    log("Flipper BLE service not found")
                    showProgress(false)
                    return@runOnUI
                }

                bleRxChar = service.getCharacteristic(FLIPPER_BLE_RX)
                bleTxChar = service.getCharacteristic(FLIPPER_BLE_TX)

                // Enable notifications
                gatt.setCharacteristicNotification(bleTxChar, true)
                bleTxChar?.getDescriptor(CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }

                isConnected.set(true)
                connectionType = "BLE"
                showProgress(false)
                updateUI()
                log("BLE ready!")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothCharacteristic) {
            char.value?.let { data ->
                mainHandler.post {
                    responseBuffer.append(String(data))
                    if (responseBuffer.contains(">:")) {
                        responseCallback?.invoke(responseBuffer.toString())
                        responseBuffer.clear()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBLECommand(cmd: String): String {
        val gatt = bluetoothGatt ?: return "BLE not connected"
        val rx = bleRxChar ?: return "BLE not ready"

        return try {
            responseBuffer.clear()
            waitingForResponse = true

            rx.value = "$cmd\r".toByteArray()
            gatt.writeCharacteristic(rx)

            // Wait for response
            val start = System.currentTimeMillis()
            while (waitingForResponse && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50)
            }

            responseBuffer.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // =====================
    // DISCONNECT
    // =====================

    private fun disconnect() {
        // Stop USB IO Manager first
        usbIoManager?.stop()
        usbIoManager = null

        // Close USB port
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB", e)
        }
        usbSerialPort = null

        // Close BLE
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BLE", e)
        }
        bluetoothGatt = null

        isConnected.set(false)
        connectionType = "NONE"
        updateUI()
        log("Disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        usbExecutor.shutdown()
    }

    // =====================
    // UI HELPERS
    // =====================

    private fun updateUI() {
        val connected = isConnected.get()

        btnConnect.text = if (connected) "DISCONNECT" else "CONNECT"
        btnTest.isEnabled = connected
        btnRFID.isEnabled = connected
        btnSubGHz.isEnabled = connected

        if (connected) {
            statusDot.setBackgroundResource(R.drawable.indicator_status_connected)
            statusText.text = connectionType
            statusText.setTextColor(resources.getColor(R.color.status_connected, null))
        } else {
            statusDot.setBackgroundResource(R.drawable.indicator_status)
            statusText.text = "OFFLINE"
            statusText.setTextColor(resources.getColor(R.color.text_secondary, null))
        }
    }

    private fun log(msg: String) {
        mainHandler.post {
            outputText.append("$msg\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
        Log.d(TAG, msg)
    }

    private fun showProgress(show: Boolean) {
        mainHandler.post {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun runOnUI(action: () -> Unit) {
        mainHandler.post(action)
    }
}
