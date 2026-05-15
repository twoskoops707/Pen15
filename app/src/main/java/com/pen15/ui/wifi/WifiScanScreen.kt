package com.pen15.ui.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.domain.awok.MarauderCli
import com.pen15.domain.awok.ScanResultParser
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.ScreenSkeleton
import com.pen15.ui.theme.Pen15Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WifiScanScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val state by ConnectionService.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var aps by remember { mutableStateOf(emptyList<ScanResultParser.Ap>()) }
    val buffer = remember { StringBuilder() }

    val cli: MarauderCli? = remember(state.awokReady, state.flipperReady) {
        when {
            state.awokReady -> ConnectionService.awok?.let { MarauderCli.direct(it) }
            state.flipperReady -> ConnectionService.flipper?.let { MarauderCli.viaFlipper(it) }
            else -> null
        }
    }

    DisposableEffect(cli) {
        val job = cli?.let {
            scope.launch {
                it.output.collect { line ->
                    buffer.append(line).append('\n')
                    aps = ScanResultParser.parse(buffer.toString())
                }
            }
        }
        onDispose { job?.cancel() }
    }

    val word = when {
        scanning -> "SCANNING"
        aps.isNotEmpty() -> "${aps.size} FOUND"
        cli == null -> "WAITING"
        else -> "READY"
    }
    val sub = when {
        cli == null -> "Plug in your AWOK to scan WiFi networks."
        scanning -> "Listening on every channel for nearby WiFi."
        aps.isNotEmpty() -> "Tap a network to pick a target."
        else -> "Tap START SCAN to find nearby networks."
    }

    ScreenSkeleton(
        nav = nav,
        title = "WiFi scan",
        statusWord = word,
        subtitle = sub,
        accent = Pen15Palette.Magenta,
        pulsing = scanning,
        body = {
            Column(modifier = Modifier.fillMaxWidth()) {
                aps.take(20).forEach { ap -> ApRow(ap) }
            }
        },
        primaryAction = {
            val s = when {
                cli == null -> PrimaryActionState.Disabled
                scanning -> PrimaryActionState.Working
                else -> PrimaryActionState.Ready
            }
            PrimaryAction(
                label = if (scanning) "STOP SCAN" else "START SCAN",
                state = s,
                onClick = onClick@{
                    val c = cli ?: return@onClick
                    if (scanning) {
                        c.stopScan()
                        scanning = false
                    } else {
                        buffer.clear()
                        aps = emptyList()
                        c.scanAp()
                        scanning = true
                        scope.launch {
                            delay(15_000)
                            if (scanning) {
                                c.stopScan()
                                scanning = false
                            }
                        }
                    }
                },
            )
        },
    )
}

@Composable
private fun ApRow(ap: ScanResultParser.Ap) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Pen15Palette.SurfaceLow.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.dp, Pen15Palette.Outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ap.ssid, color = Pen15Palette.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "${ap.bssid}  ·  ch ${ap.channel}",
                color = Pen15Palette.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "${ap.rssi} dBm",
            color = if (ap.rssi > -65) Pen15Palette.Lime else Pen15Palette.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
