package com.pen15.ui.crack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WifiPassword
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.domain.termux.CrackJobs
import com.pen15.domain.termux.TermuxRunner
import com.pen15.ui.Routes
import com.pen15.ui.components.MissionTile
import com.pen15.ui.components.Pen15Background
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.ScreenHeader
import com.pen15.ui.components.ScreenSkeleton
import com.pen15.ui.theme.Pen15Palette
import java.util.UUID

@Composable
fun CrackHomeScreen(nav: NavController) {
    Pen15Background {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Crack",
                subtitle = "Hashes and handshakes. Runs in Termux on your phone.",
                onBack = { nav.popBackStack() },
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("Handshake", "WPA / WPA2 → password",
                            Icons.Rounded.WifiPassword, Pen15Palette.Lime, true,
                            { nav.navigate(Routes.CRACK_HANDSHAKE) }, Modifier.weight(1f))
                        MissionTile("Hash", "MD5, SHA, NTLM, bcrypt",
                            Icons.Rounded.Lock, Pen15Palette.Lime, true,
                            { nav.navigate(Routes.CRACK_HASH) }, Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("Bootstrap", "One-tap Termux setup",
                            Icons.Rounded.Build, Pen15Palette.Aqua, true,
                            {
                                /* TODO: bootstrap action — start Termux job and route to viewer */
                            }, Modifier.weight(1f))
                        MissionTile("Wordlists", "Download / manage",
                            Icons.Rounded.Bolt, Pen15Palette.Aqua, true,
                            { /* TODO wordlist mgr */ }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun HandshakeCrackScreen(nav: NavController) {
    val ctx = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    ScreenSkeleton(
        nav = nav,
        title = "Handshake crack",
        statusWord = if (working) "WORKING" else "READY",
        subtitle = info ?: "Pick a saved .pcap and a wordlist, then tap CRACK. Termux runs hashcat -m 22000.",
        accent = Pen15Palette.Lime,
        pulsing = working,
        primaryAction = {
            PrimaryAction(
                label = if (working) "RUNNING…" else "CRACK",
                state = if (working) PrimaryActionState.Working else PrimaryActionState.Ready,
                onClick = {
                    working = true
                    val jobId = UUID.randomUUID().toString().take(8)
                    val cmd = CrackJobs.handshakeCrack(
                        pcapPath = "\$HOME/.pen15/captures/last.pcap",
                        wordlistPath = "\$HOME/.pen15/wordlists/rockyou.txt",
                        jobId = jobId,
                    )
                    val sent = TermuxRunner.run(ctx, cmd, background = true)
                    info = if (sent) "Started in Termux. Tail: \$HOME/.pen15/jobs/$jobId/stdout.log"
                           else "Couldn't start Termux. Make sure it's installed and allow-external-apps is set."
                    working = false
                },
            )
        },
    )
}

@Composable
fun HashCrackScreen(nav: NavController) {
    val ctx = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    ScreenSkeleton(
        nav = nav,
        title = "Hash crack",
        statusWord = if (working) "WORKING" else "READY",
        subtitle = info ?: "Paste a hash on the next screen (coming soon). For now this triggers a sample MD5 run.",
        accent = Pen15Palette.Lime,
        pulsing = working,
        primaryAction = {
            PrimaryAction(
                label = if (working) "RUNNING…" else "DEMO RUN",
                state = if (working) PrimaryActionState.Working else PrimaryActionState.Ready,
                onClick = {
                    working = true
                    val jobId = UUID.randomUUID().toString().take(8)
                    val cmd = CrackJobs.hashCrack(
                        hash = "5f4dcc3b5aa765d61d8327deb882cf99", // "password"
                        modeId = 0,
                        wordlistPath = "\$HOME/.pen15/wordlists/rockyou.txt",
                        jobId = jobId,
                    )
                    val sent = TermuxRunner.run(ctx, cmd, background = true)
                    info = if (sent) "Started job $jobId in Termux."
                           else "Couldn't reach Termux. Open Termux once, run `termux-reload-settings`, try again."
                    working = false
                },
            )
        },
    )
}
