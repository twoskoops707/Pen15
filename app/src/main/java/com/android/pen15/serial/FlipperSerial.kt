package com.android.pen15.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

class FlipperSerial(private val context: Context) : SerialInputOutputManager.Listener {

    companion object {
        const val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
        const val FLIPPER_VID = 0x0483
        const val FLIPPER_PID = 0x5740
        const val ESP32_CP210X_VID = 0x10C4
        const val ESP32_CH340_VID = 0x1A86
        const val BAUD_RATE = 115200
        const val WRITE_TIMEOUT = 200
        private val PROMPT_REGEX = Regex(">:\\s")
        private val SUBSHELL_REGEX = Regex("\\[(nfc|subghz|ir|rfid)\\]>")
    }

    enum class DeviceType { NONE, FLIPPER, ESP32 }
    enum class CliState { IDLE, WAKING, READY, BUSY }

    var listener: SerialListener? = null
    var connected = false
        private set
    var deviceType = DeviceType.NONE
        private set
    var cliState = CliState.IDLE
        private set
    var bridgeMode = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var usbManager: UsbManager? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val commandQueue = mutableListOf<String>()
    private var pendingCallback: ((String) -> Unit)? = null
    private val responseBuffer = StringBuilder()
    private var responseTimeoutRunnable: Runnable? = null
    private var currentCommand: String? = null
    private var ignorePromptUntil = 0L

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
                        device?.let { openDevice(it) }
                    } else {
                        listener?.onSerialError("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                }
            }
        }
    }

    fun register() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() {
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    fun scanAndConnect(filterVid: Int? = null) {
        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            listener?.onSerialError("No USB serial devices found")
            return
        }

        val driver = if (filterVid != null) {
            drivers.find { it.device.vendorId == filterVid }
        } else {
            drivers.find { it.device.vendorId == FLIPPER_VID && it.device.productId == FLIPPER_PID }
                ?: drivers.find { it.device.vendorId == ESP32_CP210X_VID || it.device.vendorId == ESP32_CH340_VID }
                ?: drivers.firstOrNull()
        }

        if (driver == null) {
            listener?.onSerialError("No matching device found")
            return
        }

        val device = driver.device
        if (manager.hasPermission(device)) {
            openDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, pi)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val manager = usbManager ?: throw Exception("USB Manager null")
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw Exception("No driver for device")

            usbConnection = manager.openDevice(device) ?: throw Exception("Cannot open device")
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true

            ioManager = SerialInputOutputManager(usbSerialPort, this)
            ioManager?.start()

            deviceType = when {
                device.vendorId == FLIPPER_VID -> DeviceType.FLIPPER
                device.vendorId == ESP32_CP210X_VID || device.vendorId == ESP32_CH340_VID -> DeviceType.ESP32
                else -> DeviceType.FLIPPER
            }

            connected = true
            bridgeMode = false
            cliState = CliState.WAKING
            commandQueue.clear()

            handler.postDelayed({ writeRaw(byteArrayOf(0x03)) }, 300)
            handler.postDelayed({ writeRaw("\r".toByteArray()) }, 600)
            handler.postDelayed({ writeRaw("\r".toByteArray()) }, 900)

            val name = if (deviceType == DeviceType.ESP32) "ESP32/Marauder" else "Flipper Zero"
            listener?.onSerialConnect("$name @ $BAUD_RATE baud")
        } catch (e: Exception) {
            listener?.onSerialError("Connection failed: ${e.message}")
            disconnect()
        }
    }

    fun disconnect() {
        val wasConnected = connected
        connected = false
        deviceType = DeviceType.NONE
        cliState = CliState.IDLE
        bridgeMode = false
        commandQueue.clear()
        cancelResponseTimeout()

        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            usbSerialPort?.dtr = false
            usbSerialPort?.rts = false
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null

        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null

        if (wasConnected) listener?.onSerialDisconnect()
    }

    fun sendCommand(cmd: String, callback: ((String) -> Unit)? = null) {
        if (!connected) {
            listener?.onSerialError("Not connected")
            return
        }

        if (bridgeMode) {
            currentCommand = cmd
            listener?.onCommandStarted(cmd)
            responseBuffer.clear()
            writeRaw("$cmd\r\n".toByteArray())
            startResponseTimeout(getTimeoutForCommand(cmd))
            cliState = CliState.BUSY
            return
        }

        if (cliState == CliState.READY) {
            executeDirect(cmd, callback)
        } else {
            commandQueue.add(cmd)
        }
    }

    private fun getTimeoutForCommand(cmd: String): Long = when {
        cmd.startsWith("rfid") || cmd.startsWith("ikey") -> 30_000L
        cmd.startsWith("nfc field") -> 5_000L
        cmd.startsWith("nfc") -> 15_000L
        cmd.startsWith("subghz rx") -> 60_000L
        cmd.startsWith("subghz") -> 10_000L
        cmd.startsWith("ir rx") -> 60_000L
        cmd.startsWith("ir ") -> 5_000L
        cmd.startsWith("scan") || cmd.startsWith("sniff") -> 30_000L
        cmd.startsWith("attack") -> 30_000L
        cmd.startsWith("storage") -> 5_000L
        cmd.startsWith("loader") -> 3_000L
        else -> 10_000L
    }

    private fun executeDirect(cmd: String, callback: ((String) -> Unit)? = null) {
        pendingCallback = callback
        responseBuffer.clear()
        cliState = CliState.BUSY
        currentCommand = cmd
        ignorePromptUntil = System.currentTimeMillis() + 500
        listener?.onCommandStarted(cmd)
        writeRaw("$cmd\r".toByteArray())
        startResponseTimeout(getTimeoutForCommand(cmd))
    }

    fun sendCtrlC() {
        if (!connected) return
        cancelResponseTimeout()
        writeRaw(byteArrayOf(0x03))
        if (cliState == CliState.BUSY) {
            val response = responseBuffer.toString()
            currentCommand?.let { listener?.onCommandFinished(it, response) }
            pendingCallback = null
            responseBuffer.clear()
            currentCommand = null
        }
        cliState = CliState.READY
    }

    fun startBridge(callback: (() -> Unit)? = null) {
        if (!connected || deviceType != DeviceType.FLIPPER) return
        listener?.onCommandStarted("Starting ESP32 bridge...")
        writeRaw("loader open GPIO/USB-UART Bridge\r".toByteArray())
        handler.postDelayed({
            bridgeMode = true
            cliState = CliState.READY
            listener?.onCommandFinished("bridge", "ESP32 UART bridge active")
            listener?.onSerialConnect("ESP32/Marauder (via Flipper GPIO bridge)")
            callback?.invoke()
        }, 2000)
    }

    fun stopBridge() {
        if (!connected || !bridgeMode) return
        bridgeMode = false
        writeRaw(byteArrayOf(0x03))
        handler.postDelayed({
            writeRaw(byteArrayOf(0x1B))
            handler.postDelayed({
                cliState = CliState.WAKING
                writeRaw(byteArrayOf(0x03))
                handler.postDelayed({ writeRaw("\r".toByteArray()) }, 300)
                handler.postDelayed({ writeRaw("\r".toByteArray()) }, 600)
                listener?.onSerialConnect("Flipper Zero @ $BAUD_RATE baud")
            }, 200)
        }, 200)
    }

    fun sendRaw(data: String) {
        if (!connected) return
        writeRaw(data.toByteArray())
    }

    private fun writeRaw(data: ByteArray) {
        try {
            usbSerialPort?.write(data, WRITE_TIMEOUT)
        } catch (e: Exception) {
            listener?.onSerialError("Write error: ${e.message}")
        }
    }

    override fun onNewData(data: ByteArray) {
        val text = String(data)
        handler.post {
            val clean = text.replace("\r\n", "\n").replace("\r", "")
            if (clean.isEmpty()) return@post

            listener?.onSerialData(clean)

            if (bridgeMode) {
                if (cliState == CliState.BUSY) {
                    responseBuffer.append(clean)
                }
                return@post
            }

            if (cliState == CliState.BUSY) {
                responseBuffer.append(clean)
            }

            if (SUBSHELL_REGEX.containsMatchIn(clean)) {
                if (cliState != CliState.BUSY) {
                    handler.postDelayed({ writeRaw("exit\r".toByteArray()) }, 100)
                }
                return@post
            }

            if (PROMPT_REGEX.containsMatchIn(clean) || clean.contains(">:")) {
                if (cliState == CliState.BUSY && System.currentTimeMillis() < ignorePromptUntil) {
                    return@post
                }

                val wasBusy = cliState == CliState.BUSY
                cliState = CliState.READY

                if (wasBusy) {
                    cancelResponseTimeout()
                    val response = responseBuffer.toString()
                    currentCommand?.let { listener?.onCommandFinished(it, response) }
                    pendingCallback?.invoke(response)
                    pendingCallback = null
                    responseBuffer.clear()
                    currentCommand = null
                }

                processQueue()
            }
        }
    }

    override fun onRunError(e: Exception) {
        handler.post {
            val msg = e.message ?: ""
            if (msg.contains("get_status") && connected) {
                try {
                    ioManager?.stop()
                    ioManager = SerialInputOutputManager(usbSerialPort, this@FlipperSerial)
                    ioManager?.start()
                } catch (_: Exception) {
                    listener?.onSerialError("IO recovery failed")
                    disconnect()
                }
            } else {
                listener?.onSerialError("IO Error: ${e.message}")
                disconnect()
            }
        }
    }

    private fun processQueue() {
        if (commandQueue.isNotEmpty() && cliState == CliState.READY) {
            val cmd = commandQueue.removeAt(0)
            executeDirect(cmd)
        }
    }

    private fun startResponseTimeout(timeoutMs: Long = 5000L) {
        cancelResponseTimeout()
        responseTimeoutRunnable = Runnable {
            if (cliState == CliState.BUSY) {
                val response = responseBuffer.toString()
                currentCommand?.let { listener?.onCommandFinished(it, response) }
                pendingCallback?.invoke(response)
                pendingCallback = null
                responseBuffer.clear()
                currentCommand = null
                cliState = CliState.READY
                processQueue()
            }
        }
        handler.postDelayed(responseTimeoutRunnable!!, timeoutMs)
    }

    private fun cancelResponseTimeout() {
        responseTimeoutRunnable?.let { handler.removeCallbacks(it) }
        responseTimeoutRunnable = null
    }
}
