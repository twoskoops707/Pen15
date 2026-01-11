package com.android.pen15.core

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class ProcessManager(private val context: Context) {
    
    suspend fun executeCommand(command: String, useTermux: Boolean = true): ProcessResult = withContext(Dispatchers.IO) {
        if (useTermux) {
            executeViaTermux(command)
        } else {
            executeDirectly(command)
        }
    }
    
    private fun executeViaTermux(command: String): ProcessResult {
        return try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            
            context.startService(intent)
            ProcessResult(true, "Command sent to Termux", "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute via Termux", e)
            executeDirectly(command)
        }
    }
    
    private fun executeDirectly(command: String): ProcessResult {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            outputReader.forEachLine { output.appendLine(it) }
            errorReader.forEachLine { error.appendLine(it) }
            
            val exitCode = process.waitFor()
            
            ProcessResult(
                success = exitCode == 0,
                output = output.toString(),
                error = error.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command", e)
            ProcessResult(false, "", e.message ?: "Unknown error")
        }
    }
    
    companion object {
        private const val TAG = "ProcessManager"
    }
}

data class ProcessResult(
    val success: Boolean,
    val output: String,
    val error: String
)
