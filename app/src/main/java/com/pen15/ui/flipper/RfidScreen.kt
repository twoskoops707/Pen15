package com.pen15.ui.flipper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContactlessOutlined
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.ScreenSkeleton
import com.pen15.ui.theme.MonoStyle
import com.pen15.ui.theme.Pen15Palette
import kotlinx.coroutines.launch

@Composable
fun RfidScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var lastType by remember { mutableStateOf<String?>(null) }
    var lastData by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val flipper = ConnectionService.flipper

    val statusWord = when {
        working                  -> "READING"
        lastData != null         -> "GOT IT"
        flipper == null          -> "WAITING"
        else                     -> "READY"
    }
    val subtitle = when {
        working                  -> "Hold the card or fob against the back of your Flipper."
        lastData != null         -> "Tap REPLAY to make the Flipper pretend to be that card."
        flipper == null          -> "Plug in your Flipper to read RFID cards."
        else                     -> "Hold a card to the back of the Flipper, then tap READ."
    }

    ScreenSkeleton(
        nav = nav,
        title = "RFID",
        statusWord = statusWord,
        subtitle = subtitle,
        accent = Pen15Palette.Cyan,
        pulsing = working,
        body = {
            Column {
                if (lastData != null) {
                    ResultCard(type = lastType ?: "RFID", data = lastData ?: "")
                }
                if (error != null) {
                    Text(
                        error ?: "",
                        color = Pen15Palette.Crimson,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
        primaryAction = {
            val state = when {
                flipper == null -> PrimaryActionState.Disabled
                working          -> PrimaryActionState.Working
                lastData != null -> PrimaryActionState.Success
                else             -> PrimaryActionState.Ready
            }
            val label = when {
                working          -> "READING…"
                lastData != null -> "REPLAY THIS CARD"
                else             -> "READ A CARD"
            }
            PrimaryAction(
                label = label,
                state = state,
                onClick = onPrimary@{
                    if (flipper == null) return@onPrimary
                    if (working) return@onPrimary
                    if (lastData != null && lastType != null) {
                        scope.launch {
                            working = true
                            error = null
                            val ok = flipper.rfidEmulate(lastType!!, lastData!!)
                            working = false
                            if (!ok) error = "Couldn't replay. Try reading the card again."
                        }
                    } else {
                        scope.launch {
                            working = true
                            error = null
                            val res = flipper.rfidRead()
                            working = false
                            if (res != null) {
                                lastType = res.first
                                lastData = res.second
                            } else {
                                error = "Didn't pick up a card. Try again, holding the card flatter."
                            }
                        }
                    }
                },
                icon = Icons.Rounded.ContactlessOutlined,
            )
        },
    )
}

@Composable
private fun ResultCard(type: String, data: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(Pen15Palette.SurfaceLow.copy(alpha = 0.7f), shape = RoundedCornerShape(20.dp))
            .border(1.dp, Pen15Palette.Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ContactlessOutlined,
                contentDescription = null,
                tint = Pen15Palette.Cyan,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = type,
                color = Pen15Palette.Cyan,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = data,
            color = Pen15Palette.TextPrimary,
            style = MonoStyle,
        )
    }
}
