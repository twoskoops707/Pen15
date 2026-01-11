package com.android.pen15.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class FlipperUSBManager(private val context: Context) {
    
    private var usbSerialPort: UsbSerialPort? = null
    private var connected = false
    private val ACTION_USB_PERMISSION = "com.android.pen15.USB_PERMISSION"
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    } else {
                        Log.e(TAG, "USB permission denied")
                    }
                }
            }
        }
    }
    
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        
        if (availableDrivers.isEmpty()) {
            Log.e(TAG, "No USB devices found")
            return@withContext false
        }
        
        val driver = availableDrivers[0]
        val device = driver.device
        
        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            manager.requestPermission(device, permissionIntent)
            return@withContext false
        }
        
        connectToDevice(device)
    }
    
    private fun connectToDevice(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return false
        
        val connection = manager.openDevice(driver.device) ?: return false
        
        return try {
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            connected = true
            Log.i(TAG, "Connected to Flipper Zero via USB")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error connecting to USB device", e)
            false
        }
    }
    
    suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        if (!connected || usbSerialPort == null) {
            return@withContext "Error: Not connected"
        }

        try {
            // Send command with \r (carriage return only, NOT \r\n!)
            val data = "$command\r".toByteArray()
            usbSerialPort?.write(data, 2000)

            // Wait for response - read until we see the prompt ">: "
            val response = StringBuilder()
            val buffer = ByteArray(1024)
            var totalRead = 0
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 3000) {
                try {
                    val numBytesRead = usbSerialPort?.read(buffer, 500) ?: 0
                    if (numBytesRead > 0) {
                        val chunk = String(buffer, 0, numBytesRead)
                        response.append(chunk)
                        totalRead += numBytesRead

                        // Stop reading if we see the prompt
                        if (response.contains(">: ")) {
                            break
                        }
                    } else {
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    break
                }
            }

            // Remove the prompt from the response
            response.toString().replace(">: ", "").trim()
        } catch (e: IOException) {
            Log.e(TAG, "Error sending command", e)
            "Error: ${e.message}"
        }
    }
    
    fun disconnect() {
        try {
            usbSerialPort?.close()
            connected = false
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                // Receiver might not be registered
            }
            Log.i(TAG, "Disconnected from Flipper Zero")
        } catch (e: IOException) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
    
    fun isConnected(): Boolean = connected
    
    companion object {
        private const val TAG = "FlipperUSBManager"
    }
}
