package com.pen15.ui.wifi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.pen15.domain.awok.MarauderCli
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.ScreenSkeleton
import com.pen15.ui.theme.Pen15Palette

private fun cli(): MarauderCli? {
    val st = ConnectionService.state.value
    return when {
        st.awokReady -> ConnectionService.awok?.let { MarauderCli.direct(it) }
        st.flipperReady -> ConnectionService.flipper?.let { MarauderCli.viaFlipper(it) }
        else -> null
    }
}

@Composable
fun WifiAttackTemplate(
    nav: NavController,
    title: String,
    idleSubtitle: String,
    runningSubtitle: String,
    primaryReady: String,
    primaryRunning: String,
    start: (MarauderCli) -> Unit,
    stop: (MarauderCli) -> Unit = { it.stopScan() },
) {
    val state by ConnectionService.state.collectAsState()
    var running by remember { mutableStateOf(false) }
    val c = cli()
    val word = when {
        running -> "ACTIVE"
        c == null -> "WAITING"
        else -> "READY"
    }
    val sub = when {
        c == null -> "Plug in your AWOK to use this attack."
        running -> runningSubtitle
        else -> idleSubtitle
    }
    ScreenSkeleton(
        nav = nav,
        title = title,
        statusWord = word,
        subtitle = sub,
        accent = Pen15Palette.Magenta,
        pulsing = running,
        primaryAction = {
            val s = when {
                c == null -> PrimaryActionState.Disabled
                running   -> PrimaryActionState.Danger
                else      -> PrimaryActionState.Ready
            }
            PrimaryAction(
                label = if (running) primaryRunning else primaryReady,
                state = s,
                onClick = onClick@{
                    val cli = c ?: return@onClick
                    if (running) { stop(cli); running = false } else { start(cli); running = true }
                },
            )
        },
    )
}

@Composable fun DeauthScreen(nav: NavController) = WifiAttackTemplate(
    nav, "Deauth",
    idleSubtitle = "Pick a network from Scan first, then tap DEAUTH to boot devices off it.",
    runningSubtitle = "Sending deauth frames. This will keep going until you stop it.",
    primaryReady = "DEAUTH",
    primaryRunning = "STOP",
    start = { it.deauth() },
)

@Composable fun PmkidScreen(nav: NavController) = WifiAttackTemplate(
    nav, "PMKID",
    idleSubtitle = "Tap CAPTURE to listen for a WPA handshake. Move the AWOK closer to the AP for best results.",
    runningSubtitle = "Capturing PMKID frames…",
    primaryReady = "CAPTURE",
    primaryRunning = "STOP",
    start = { it.pmkid() },
)

@Composable fun EvilPortalScreen(nav: NavController) = WifiAttackTemplate(
    nav, "Evil portal",
    idleSubtitle = "Run a fake login page that captures credentials from devices that connect.",
    runningSubtitle = "Portal is up. Watch the captured creds in the AWOK output.",
    primaryReady = "START PORTAL",
    primaryRunning = "STOP",
    start = { it.evilPortal() },
)

@Composable fun BeaconSpamScreen(nav: NavController) = WifiAttackTemplate(
    nav, "Beacon spam",
    idleSubtitle = "Spawn dozens of fake networks to flood nearby phones.",
    runningSubtitle = "Flooding the air with fake beacons.",
    primaryReady = "FLOOD",
    primaryRunning = "STOP",
    start = { it.beaconSpam("rickroll") },
)

@Composable fun KarmaScreen(nav: NavController) = WifiAttackTemplate(
    nav, "Karma",
    idleSubtitle = "Auto-respond to probe requests. Devices that ask for known networks try to connect.",
    runningSubtitle = "Karma running. Devices may auto-connect.",
    primaryReady = "START KARMA",
    primaryRunning = "STOP",
    start = { it.karma() },
)

@Composable fun PacketCaptureScreen(nav: NavController) = WifiAttackTemplate(
    nav, "Packet capture",
    idleSubtitle = "Save raw 802.11 frames from nearby networks.",
    runningSubtitle = "Capturing packets…",
    primaryReady = "START CAPTURE",
    primaryRunning = "STOP",
    start = { it.probeReqSniff() },
)

@Composable fun MitmScreen(nav: NavController) = WifiAttackTemplate(
    nav, "MITM",
    idleSubtitle = "Combine evil portal + ARP poison to sit between target and gateway.",
    runningSubtitle = "MITM helper running.",
    primaryReady = "START",
    primaryRunning = "STOP",
    start = { it.evilPortal() },
)

@Composable fun BleSpamScreen(nav: NavController) = WifiAttackTemplate(
    nav, "BLE spam",
    idleSubtitle = "Send Bluetooth advertisement floods to nearby phones.",
    runningSubtitle = "BLE spam running.",
    primaryReady = "FLOOD",
    primaryRunning = "STOP",
    start = { it.bleSpam() },
)
