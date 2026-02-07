package com.android.pen15.serial

interface SerialListener {
    fun onSerialConnect(deviceName: String)
    fun onSerialData(data: String)
    fun onSerialError(error: String)
    fun onSerialDisconnect()
    fun onCommandStarted(cmd: String) {}
    fun onCommandFinished(cmd: String, response: String) {}
}
