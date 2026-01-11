package com.android.pen15.ui.base

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.core.ConnectionManager
import com.android.pen15.core.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

abstract class BaseToolActivity : AppCompatActivity() {

    protected lateinit var outputText: TextView
    protected lateinit var progressBar: ProgressBar
    protected lateinit var executeButton: Button
    protected val connectionManager = ConnectionManager.getInstance()
    protected val processManager by lazy { ProcessManager(this) }
    private val outputBuilder = StringBuilder()
    
    abstract fun getToolName(): String
    abstract fun getLayoutResource(): Int
    abstract fun onToolExecute()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(getLayoutResource())
            outputText = findViewById(R.id.outputText)
            progressBar = findViewById(R.id.progressBar)
            executeButton = findViewById(R.id.btnExecute)

            setupToolButtons()
            logMessage("${getToolName()} initialized")
        } catch (e: Exception) {
            setContentView(R.layout.activity_generic_tool)
            outputText = findViewById(R.id.outputText)
            progressBar = findViewById(R.id.progressBar)
            executeButton = findViewById(R.id.btnExecute)
            setupToolButtons()
            logMessage("${getToolName()} - Ready")
        }
    }
    
    private fun setupToolButtons() {
        try {
            findViewById<Button>(R.id.btnExecute)?.setOnClickListener {
                onToolExecute()
            }
        } catch (e: Exception) {
            // Button might not exist
        }
    }
    
    protected fun showProgress(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }
    
    protected fun logMessage(text: String, includeTimestamp: Boolean = true) {
        runOnUiThread {
            val timestamp = if (includeTimestamp) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            } else ""
            
            val message = if (includeTimestamp) "[$timestamp] $text" else text
            outputBuilder.appendLine(message)
            outputText.text = outputBuilder.toString()
            
            // Auto-scroll to bottom
            outputText.post {
                val scrollView = outputText.parent as? android.widget.ScrollView
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    protected fun clearOutput() {
        runOnUiThread {
            outputBuilder.clear()
            outputText.text = ""
        }
    }
    
    protected suspend fun sendFlipperCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                connectionManager.sendCommand(command)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }
    
    protected suspend fun executeTermuxCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val result = processManager.executeCommand(command, useTermux = true)
                if (result.success) result.output else result.error
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }
    
    protected fun executeCommandWithProgress(command: String, useTermux: Boolean = false) {
        lifecycleScope.launch {
            showProgress(true)

            val result = if (useTermux) {
                executeTermuxCommand(command)
            } else {
                sendFlipperCommand(command)
            }

            logMessage(result)
            showProgress(false)
        }
    }

    // Check if Flipper Zero is connected
    protected suspend fun checkFlipperConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check if USB device exists
                val result = processManager.executeCommand("ls /dev/ttyACM0", useTermux = false)
                result.success
            } catch (e: Exception) {
                false
            }
        }
    }

    // Backward compatibility method
    protected fun appendOutput(text: String) {
        logMessage(text, includeTimestamp = false)
    }
}
