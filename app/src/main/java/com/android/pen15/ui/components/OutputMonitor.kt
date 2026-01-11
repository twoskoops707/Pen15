package com.android.pen15.ui.components

import android.content.Context
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.android.pen15.R

class OutputMonitor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    private val outputText: TextView
    private val outputBuilder = StringBuilder()
    
    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.glass_surface))
        setPadding(16, 16, 16, 16)
        
        outputText = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.text_primary))
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }
        
        addView(outputText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    
    fun appendOutput(text: String) {
        outputBuilder.appendLine(text)
        outputText.text = outputBuilder.toString()
        
        // Auto-scroll to bottom
        outputText.post {
            val scrollAmount = outputText.layout?.getLineTop(outputText.lineCount) ?: 0
            outputText.scrollTo(0, scrollAmount - outputText.height)
        }
    }
    
    fun clear() {
        outputBuilder.clear()
        outputText.text = ""
    }
    
    fun setOutput(text: String) {
        outputBuilder.clear()
        outputBuilder.append(text)
        outputText.text = text
    }
}
