package com.android.pen15.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.android.pen15.serial.FlipperSerial

class AppState : ViewModel() {

    private val _terminalOutput = MutableLiveData<String>()
    val terminalOutput: LiveData<String> = _terminalOutput

    private val _connectionStatus = MutableLiveData("OFFLINE")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _deviceName = MutableLiveData("No device connected")
    val deviceName: LiveData<String> = _deviceName

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    private val _deviceType = MutableLiveData(FlipperSerial.DeviceType.NONE)
    val deviceType: LiveData<FlipperSerial.DeviceType> = _deviceType

    private val _activeCommand = MutableLiveData<String?>(null)
    val activeCommand: LiveData<String?> = _activeCommand

    data class CommandResult(val cmd: String, val response: String)

    private val _lastResponse = MutableLiveData<CommandResult?>(null)
    val lastResponse: LiveData<CommandResult?> = _lastResponse

    var lastRfidData: Pair<String, String>? = null
    var lastIkeyData: Pair<String, String>? = null
    var lastNfcPath: String? = null

    fun setCommandStarted(cmd: String) {
        _activeCommand.postValue(cmd)
    }

    fun setCommandFinished(cmd: String, response: String) {
        _activeCommand.postValue(null)
        _lastResponse.postValue(CommandResult(cmd, response))
        parseReadResponse(cmd, response)
    }

    private fun parseReadResponse(cmd: String, response: String) {
        if (cmd.contains("rfid read") || cmd.contains("rfid")) {
            val typeMatch = Regex("Key type:\\s*(\\S+)").find(response)
                ?: Regex("Protocol:\\s*(\\S+)").find(response)
            val dataMatch = Regex("Key data:\\s*(\\S+)").find(response)
                ?: Regex("Data:\\s*(\\S+)").find(response)
            if (typeMatch != null && dataMatch != null) {
                lastRfidData = Pair(typeMatch.groupValues[1], dataMatch.groupValues[1])
            }
        }
        if (cmd.contains("ikey read") || cmd.contains("ikey")) {
            val typeMatch = Regex("Key type:\\s*(\\S+)").find(response)
                ?: Regex("Type:\\s*(\\S+)").find(response)
            val dataMatch = Regex("Key data:\\s*(\\S+)").find(response)
                ?: Regex("Data:\\s*(\\S+)").find(response)
            if (typeMatch != null && dataMatch != null) {
                lastIkeyData = Pair(typeMatch.groupValues[1], dataMatch.groupValues[1])
            }
        }
        if (cmd.contains("nfc") && cmd.contains("scanner")) {
            val pathMatch = Regex("Saved to:\\s*(\\S+)").find(response)
            if (pathMatch != null) {
                lastNfcPath = pathMatch.groupValues[1]
            }
        }
    }

    private val terminalLines = mutableListOf<String>()
    private val commandHistory = mutableListOf<String>()
    val history: List<String> get() = commandHistory

    fun appendOutput(text: String) {
        terminalLines.add(text)
        if (terminalLines.size > 500) {
            terminalLines.removeAt(0)
        }
        _terminalOutput.postValue(text)
    }

    fun clearOutput() {
        terminalLines.clear()
    }

    fun getFullOutput(): String = terminalLines.joinToString("")

    fun setConnected(connected: Boolean, type: FlipperSerial.DeviceType, name: String) {
        _connected.postValue(connected)
        _deviceType.postValue(type)
        _deviceName.postValue(name)
        _connectionStatus.postValue(
            when (type) {
                FlipperSerial.DeviceType.FLIPPER -> "FLIPPER"
                FlipperSerial.DeviceType.ESP32 -> "ESP32"
                FlipperSerial.DeviceType.NONE -> "OFFLINE"
            }
        )
    }

    fun setDisconnected() {
        _connected.postValue(false)
        _deviceType.postValue(FlipperSerial.DeviceType.NONE)
        _deviceName.postValue("No device connected")
        _connectionStatus.postValue("OFFLINE")
    }

    fun addToHistory(cmd: String) {
        commandHistory.remove(cmd)
        commandHistory.add(0, cmd)
        if (commandHistory.size > 50) commandHistory.removeAt(commandHistory.size - 1)
    }
}
