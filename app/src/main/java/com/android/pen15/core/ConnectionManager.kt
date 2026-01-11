package com.android.pen15.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionManager private constructor() {

    private var usbManager: FlipperUSBManager? = null
    private var bluetoothManager: FlipperBluetoothManager? = null
    private var connected = false
    private var connectionType: ConnectionType = ConnectionType.NONE

    enum class ConnectionType {
        NONE, USB, BLUETOOTH
    }

    fun isConnected(): Boolean = connected

    fun getConnectionType(): ConnectionType = connectionType

    suspend fun connectUSB(context: Context, callback: (Boolean) -> Unit) = withContext(Dispatchers.Main) {
        disconnect() // Disconnect any existing connection

        usbManager = FlipperUSBManager(context)
        val result = withContext(Dispatchers.IO) {
            usbManager?.connect() ?: false
        }

        connected = result
        if (result) {
            connectionType = ConnectionType.USB
            Log.d("ConnectionManager", "Connected via USB")
        }
        callback(result)
    }

    suspend fun connectBluetooth(context: Context, callback: (Boolean) -> Unit) {
        disconnect() // Disconnect any existing connection

        bluetoothManager = FlipperBluetoothManager(context)
        val result = withContext(Dispatchers.IO) {
            bluetoothManager?.connect() ?: false
        }

        connected = result
        if (result) {
            connectionType = ConnectionType.BLUETOOTH
            Log.d("ConnectionManager", "Connected via Bluetooth")
        }

        withContext(Dispatchers.Main) {
            callback(result)
        }
    }

    suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            when (connectionType) {
                ConnectionType.USB -> usbManager?.sendCommand(command) ?: "Error: Not connected"
                ConnectionType.BLUETOOTH -> bluetoothManager?.sendCommand(command) ?: "Error: Not connected"
                ConnectionType.NONE -> "Error: Not connected"
            }
        }
    }

    fun disconnect() {
        usbManager?.disconnect()
        bluetoothManager?.disconnect()
        usbManager = null
        bluetoothManager = null
        connected = false
        connectionType = ConnectionType.NONE
        Log.d("ConnectionManager", "Disconnected")
    }
    
    companion object {
        @Volatile
        private var INSTANCE: ConnectionManager? = null
        
        fun getInstance(): ConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionManager().also { INSTANCE = it }
            }
        }
    }
}
