package com.pen15.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.theme.Pen15Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ConnectionService.ensureStarted(this)
        setContent {
            Pen15Theme {
                Pen15Nav()
            }
        }
    }
}
