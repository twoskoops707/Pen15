package com.android.pen15.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class FlipperBluetoothManager(private val context: Context) {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    companion object {
        private const val TAG = "FlipperBluetooth"
        // Flipper Zero Bluetooth Serial UUID
        private val SERIAL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val FLIPPER_NAME_PREFIX = "Flipper"
    }

    fun connect(): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth not supported")
                return false
            }

            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "Bluetooth is disabled")
                return false
            }

            // Check permissions
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                return false
            }

            // Find paired Flipper Zero device
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            var flipperDevice: BluetoothDevice? = null

            pairedDevices?.forEach { device ->
                if (device.name?.startsWith(FLIPPER_NAME_PREFIX) == true) {
                    flipperDevice = device
                    Log.d(TAG, "Found Flipper: ${device.name} - ${device.address}")
                }
            }

            if (flipperDevice == null) {
                Log.e(TAG, "No paired Flipper Zero found")
                return false
            }

            // Connect to Flipper via Bluetooth Serial
            Log.d(TAG, "Connecting to ${flipperDevice!!.name}...")
            bluetoothSocket = flipperDevice!!.createRfcommSocketToServiceRecord(SERIAL_UUID)

            // Cancel discovery to speed up connection
            bluetoothAdapter?.cancelDiscovery()

            // Connect
            bluetoothSocket?.connect()

            if (bluetoothSocket?.isConnected == true) {
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream

                Log.d(TAG, "Connected to Flipper Zero via Bluetooth!")

                // Send initial handshake
                Thread.sleep(500)
                sendCommand("version")

                return true
            }

        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            disconnect()
        }

        return false
    }

    fun sendCommand(command: String): String {
        try {
            if (bluetoothSocket?.isConnected != true) {
                return "Error: Not connected"
            }

            val commandBytes = "$command\r\n".toByteArray()
            outputStream?.write(commandBytes)
            outputStream?.flush()

            // Wait for response
            Thread.sleep(300)

            val buffer = ByteArray(1024)
            val bytesRead = inputStream?.available() ?: 0

            if (bytesRead > 0) {
                inputStream?.read(buffer, 0, bytesRead)
                val response = String(buffer, 0, bytesRead).trim()
                Log.d(TAG, "Response: $response")
                return response
            }

            return "OK"

        } catch (e: IOException) {
            Log.e(TAG, "Command failed: ${e.message}")
            return "Error: ${e.message}"
        }
    }

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()

            inputStream = null
            outputStream = null
            bluetoothSocket = null

            Log.d(TAG, "Disconnected from Flipper Zero")
        } catch (e: IOException) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true
    }
}
