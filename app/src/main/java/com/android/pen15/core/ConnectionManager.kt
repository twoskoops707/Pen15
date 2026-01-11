package com.android.pen15.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionManager private constructor() {
    
    private var usbManager: FlipperUSBManager? = null
    private var connected = false
    private var connectionType: ConnectionType = ConnectionType.NONE
    
    enum class ConnectionType {
        NONE, USB, BLUETOOTH
    }
    
    fun isConnected(): Boolean = connected
    
    fun getConnectionType(): ConnectionType = connectionType
    
    suspend fun connectUSB(context: Context, callback: (Boolean) -> Unit) = withContext(Dispatchers.Main) {
        usbManager = FlipperUSBManager(context)
        val result = withContext(Dispatchers.IO) {
            usbManager?.connect() ?: false
        }
        
        connected = result
        if (result) {
            connectionType = ConnectionType.USB
        }
        callback(result)
    }
    
    suspend fun connectBluetooth(context: Context, callback: (Boolean) -> Unit) {
        // TODO: Implement Bluetooth connection
        withContext(Dispatchers.Main) {
            callback(false)
        }
    }
    
    suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            usbManager?.sendCommand(command) ?: "Error: Not connected"
        }
    }
    
    fun disconnect() {
        usbManager?.disconnect()
        connected = false
        connectionType = ConnectionType.NONE
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
