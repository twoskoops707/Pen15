package com.android.pen15

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionTestActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTest: Button
    private lateinit var btnDeviceInfo: Button
    private lateinit var btnHelp: Button

    private var usbPort: UsbSerialPort? = null
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_test)

        outputText = findViewById(R.id.outputText)
        btnConnect = findViewById(R.id.btnConnect)
        btnTest = findViewById(R.id.btnTest)
        btnDeviceInfo = findViewById(R.id.btnDeviceInfo)
        btnHelp = findViewById(R.id.btnHelp)

        log("=== FLIPPER CONNECTION TEST ===")
        log("App started")

        btnConnect.setOnClickListener {
            lifecycleScope.launch { connectUSB() }
        }

        btnTest.setOnClickListener {
            lifecycleScope.launch { sendTestCommand() }
        }

        btnDeviceInfo.setOnClickListener {
            lifecycleScope.launch { sendCommand("device_info") }
        }

        btnHelp.setOnClickListener {
            lifecycleScope.launch { sendCommand("help") }
        }

        // Auto-scan for devices
        lifecycleScope.launch { scanDevices() }
    }

    private suspend fun scanDevices() = withContext(Dispatchers.IO) {
        log("\n--- Scanning for USB devices ---")
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = manager.deviceList

        log("Found ${deviceList.size} USB device(s)")

        deviceList.values.forEachIndexed { index, device ->
            log("\nDevice $index:")
            log("  Name: ${device.deviceName}")
            log("  Vendor ID: ${device.vendorId} (0x${device.vendorId.toString(16)})")
            log("  Product ID: ${device.productId} (0x${device.productId.toString(16)})")
            log("  Class: ${device.deviceClass}")
            log("  Protocol: ${device.deviceProtocol}")
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        log("\nFound ${availableDrivers.size} compatible serial device(s)")

        availableDrivers.forEachIndexed { index, driver ->
            log("\nSerial Device $index:")
            log("  Driver: ${driver.javaClass.simpleName}")
            log("  Ports: ${driver.ports.size}")
        }
    }

    private suspend fun connectUSB() = withContext(Dispatchers.IO) {
        log("\n--- Attempting USB Connection ---")

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            log("ERROR: No USB serial devices found")
            log("\nIs Flipper connected?")
            log("If yes, try: GPIO → USB-UART Bridge on Flipper")
            return@withContext
        }

        val driver = availableDrivers[0]
        val device = driver.device

        log("Found device: ${device.deviceName}")
        log("Vendor: 0x${device.vendorId.toString(16)}")
        log("Product: 0x${device.productId.toString(16)}")

        if (!manager.hasPermission(device)) {
            log("ERROR: No USB permission - requesting...")
            // TODO: Request permission properly
            return@withContext
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            log("ERROR: Failed to open USB device")
            return@withContext
        }

        log("USB device opened successfully")

        try {
            usbPort = driver.ports[0]
            usbPort?.open(connection)

            // Try different baud rates
            log("Setting serial parameters: 115200 baud, 8N1")
            usbPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            connected = true
            log("✓ CONNECTION SUCCESSFUL")
            log("\nReady to send commands!")

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Log.e(TAG, "Connection error", e)
        }
    }

    private suspend fun sendTestCommand() {
        log("\n--- Sending Test Command ---")

        // Just send newline to see if we get a prompt
        sendRaw("\r")
    }

    private suspend fun sendCommand(cmd: String) = withContext(Dispatchers.IO) {
        log("\n--- Sending: $cmd ---")

        if (!connected || usbPort == null) {
            log("ERROR: Not connected")
            return@withContext
        }

        try {
            // Send command with carriage return
            val data = "$cmd\r".toByteArray()
            log("Sending ${data.size} bytes: ${data.joinToString(" ") { "0x%02X".format(it) }}")

            usbPort?.write(data, 1000)
            log("Command sent")

            // Read response
            log("Reading response...")
            val buffer = ByteArray(1024)
            val response = StringBuilder()
            var totalRead = 0

            // Read for up to 3 seconds
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                val bytesRead = try {
                    usbPort?.read(buffer, 200) ?: 0
                } catch (e: Exception) {
                    log("Read timeout - waiting for more data...")
                    0
                }

                if (bytesRead > 0) {
                    val chunk = String(buffer, 0, bytesRead)
                    response.append(chunk)
                    totalRead += bytesRead
                    log("Read $bytesRead bytes (total: $totalRead)")

                    // Check if we got the prompt
                    if (response.contains(">:") || response.contains(">")) {
                        log("Got prompt - response complete")
                        break
                    }
                }
            }

            log("\n=== RESPONSE (${response.length} chars) ===")
            if (response.isEmpty()) {
                log("(empty response)")
            } else {
                log(response.toString())
            }
            log("=== END RESPONSE ===")

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Log.e(TAG, "Send error", e)
        }
    }

    private suspend fun sendRaw(data: String) = withContext(Dispatchers.IO) {
        if (!connected || usbPort == null) {
            log("ERROR: Not connected")
            return@withContext
        }

        try {
            val bytes = data.toByteArray()
            usbPort?.write(bytes, 1000)
            log("Sent raw: ${bytes.joinToString(" ") { "0x%02X".format(it) }}")

            // Read whatever comes back
            Thread.sleep(500)
            val buffer = ByteArray(256)
            val bytesRead = usbPort?.read(buffer, 500) ?: 0

            if (bytesRead > 0) {
                val response = String(buffer, 0, bytesRead)
                log("Response: $response")
            } else {
                log("No response")
            }
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            outputText.append("$message\n")

            // Auto-scroll to bottom
            val scrollView = outputText.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            usbPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing port", e)
        }
    }

    companion object {
        private const val TAG = "ConnectionTest"
    }
}
