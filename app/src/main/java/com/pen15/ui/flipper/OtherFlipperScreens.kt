package com.pen15.ui.flipper

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.ScreenSkeleton
import com.pen15.ui.theme.Pen15Palette
import kotlinx.coroutines.launch

/**
 * Generic Flipper feature screen that drives a single suspend operation
 * on the FAP. Wraps "press → flipper does thing → show result" in one
 * tiny shape.
 */
@Composable
fun FlipperFeatureScreen(
    nav: NavController,
    title: String,
    idleSubtitle: String,
    workingSubtitle: String,
    primaryReady: String,
    primaryWorking: String = "WORKING…",
    op: suspend () -> String?,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val flipper = ConnectionService.flipper
    val statusWord = when {
        working          -> "WORKING"
        result != null   -> "GOT IT"
        flipper == null  -> "WAITING"
        else             -> "READY"
    }
    val subtitle = when {
        working          -> workingSubtitle
        result != null   -> result ?: ""
        flipper == null  -> "Plug in your Flipper to start."
        else             -> idleSubtitle
    }
    ScreenSkeleton(
        nav = nav,
        title = title,
        statusWord = statusWord,
        subtitle = subtitle,
        accent = Pen15Palette.Cyan,
        pulsing = working,
        body = {
            if (error != null) {
                Text(
                    error ?: "",
                    color = Pen15Palette.Crimson,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
        primaryAction = {
            val state = when {
                flipper == null -> PrimaryActionState.Disabled
                working         -> PrimaryActionState.Working
                result != null  -> PrimaryActionState.Success
                else            -> PrimaryActionState.Ready
            }
            PrimaryAction(
                label = if (working) primaryWorking else primaryReady,
                state = state,
                onClick = onPrimary@{
                    if (flipper == null || working) return@onPrimary
                    scope.launch {
                        working = true; error = null
                        val r = op()
                        working = false
                        if (r != null) result = r else error = "No response from the Flipper. Try again."
                    }
                },
            )
        },
    )
}

@Composable fun NfcScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "NFC",
        idleSubtitle = "Hold a card or phone against the back of your Flipper, then tap READ.",
        workingSubtitle = "Looking for an NFC tag…",
        primaryReady = "READ A TAG",
    ) {
        ConnectionService.flipper?.nfcDetect()?.let { (type, uid) -> "$type — $uid" }
    }
}

@Composable fun SubGhzScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "Sub-GHz",
        idleSubtitle = "Tap LISTEN to scan 433 MHz for nearby remotes (10 s).",
        workingSubtitle = "Listening on 433.92 MHz…",
        primaryReady = "LISTEN 10 s",
    ) {
        val n = ConnectionService.flipper?.subghzRx(433_920_000L, 10_000L) ?: 0
        "Heard $n bursts. Use RECORD to capture a remote."
    }
}

@Composable fun IrScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "Infrared",
        idleSubtitle = "Point a remote at the Flipper, then tap LEARN.",
        workingSubtitle = "Waiting for a button press on the remote…",
        primaryReady = "LEARN A BUTTON",
    ) {
        ConnectionService.flipper?.irRx()?.let { "${it.first} addr=${it.second} cmd=${it.third}" }
    }
}

@Composable fun IButtonScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "iButton",
        idleSubtitle = "Hold the iButton key against the back of your Flipper.",
        workingSubtitle = "Reading…",
        primaryReady = "READ KEY",
    ) {
        ConnectionService.flipper?.ikeyRead()?.let { "${it.first} — ${it.second}" }
    }
}

@Composable fun BadUsbScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "Bad USB",
        idleSubtitle = "Pick a DuckyScript and run it on a target machine.",
        workingSubtitle = "Sending keystrokes…",
        primaryReady = "OPEN SCRIPT LIBRARY",
    ) {
        // BadUSB execution is initiated on the Flipper; phone-side flow
        // is "open script library" which we'll wire to a script picker
        // in a follow-up. For now we surface a stub.
        "Script library is coming next. Use Flipper UI for now."
    }
}

@Composable fun GpioScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "GPIO",
        idleSubtitle = "Toggle a pin or talk to AWOK over UART.",
        workingSubtitle = "…",
        primaryReady = "TOGGLE PIN 5",
    ) {
        val ok = ConnectionService.flipper?.let {
            it.gpioMode(5, true) && it.gpioWrite(5, 1)
        } ?: false
        if (ok) "Pin 5 is now HIGH." else null
    }
}

@Composable fun BluetoothScreen(nav: NavController) {
    FlipperFeatureScreen(
        nav, title = "Bluetooth",
        idleSubtitle = "BLE scanning is in the works.",
        workingSubtitle = "…",
        primaryReady = "COMING SOON",
    ) { "Bluetooth scanning is coming next." }
}
