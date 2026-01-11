package com.android.pen15.utils

import android.content.Context
import com.android.pen15.core.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ToolChecker {
    
    suspend fun checkToolAvailable(context: Context, toolName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val processManager = ProcessManager(context)
                val result = processManager.executeCommand("which $toolName", useTermux = true)
                result.success && result.output.isNotBlank()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun checkFlipperConnected(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check if Flipper is connected via USB
                val processManager = ProcessManager(context)
                val result = processManager.executeCommand("ls /dev/ttyACM0", useTermux = false)
                result.success
            } catch (e: Exception) {
                false
            }
        }
    }
    
    fun getInstallCommand(toolName: String): String {
        return when (toolName) {
            "nmap" -> "pkg install nmap"
            "aircrack-ng", "aireplay-ng", "airodump-ng", "airmon-ng" -> "pkg install aircrack-ng"
            "tcpdump" -> "pkg install tcpdump"
            "hcitool" -> "pkg install bluez-utils"
            "arpspoof" -> "pkg install dsniff"
            "hashcat" -> "pkg install hashcat"
            else -> "pkg install $toolName"
        }
    }
}
