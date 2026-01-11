package com.android.pen15.ui.base

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.pen15.R
import com.android.pen15.core.ConnectionManager
import com.android.pen15.core.ProcessManager
import kotlinx.coroutines.launch

abstract class BaseToolActivity : AppCompatActivity() {
    
    protected lateinit var outputText: TextView
    protected val connectionManager = ConnectionManager.getInstance()
    protected val processManager by lazy { ProcessManager(this) }
    
    abstract fun getToolName(): String
    abstract fun getLayoutResource(): Int
    abstract fun onToolExecute()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(getLayoutResource())
            outputText = findViewById(R.id.outputText)
            
            setupToolButtons()
        } catch (e: Exception) {
            setContentView(R.layout.activity_generic_tool)
            outputText = findViewById(R.id.outputText)
            outputText.text = "${getToolName()} - Ready..."
        }
    }
    
    private fun setupToolButtons() {
        try {
            findViewById<Button>(R.id.btnExecute)?.setOnClickListener {
                onToolExecute()
            }
        } catch (e: Exception) {
        }
    }
    
    protected fun appendOutput(text: String) {
        runOnUiThread {
            outputText.append("\n$text")
        }
    }
    
    protected suspend fun sendFlipperCommand(command: String): String {
        return connectionManager.sendCommand(command)
    }
    
    protected suspend fun executeTermuxCommand(command: String) {
        val result = processManager.executeCommand(command)
        appendOutput(if (result.success) result.output else result.error)
    }
}
