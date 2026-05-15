package com.pen15.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pen15.domain.connection.ConnectionService
import com.pen15.domain.connection.ConnectionState
import com.pen15.domain.engagement.EngagementRepository
import com.pen15.ui.Routes
import com.pen15.ui.components.ChipState
import com.pen15.ui.components.HardwareChip
import com.pen15.ui.components.MissionTile
import com.pen15.ui.components.Pen15Background
import com.pen15.ui.theme.Pen15Palette

@Composable
fun HomeScreen(nav: NavController) {
    val state by ConnectionService.state.collectAsState()
    val engagement by EngagementRepository.active.collectAsState()

    Pen15Background {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = 32.dp,
            ),
        ) {
            item { TopBar() }
            item { EngagementBar(engagement?.clientName, onClick = {
                nav.navigate(if (engagement == null) Routes.ENGAGEMENT_NEW else Routes.ENGAGEMENT_LIST)
            }) }
            item { Spacer(Modifier.height(20.dp)) }
            item { HardwareRow(state) }
            item { Spacer(Modifier.height(28.dp)) }
            item { Pen15Sections(nav) }
            item { Spacer(Modifier.height(20.dp)) }
            item { LegalFooter() }
            item {
                Spacer(
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "PEN15",
                color = Pen15Palette.TextPrimary,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                ),
            )
            Text(
                "PUSH-BUTTON PEN-TESTING",
                color = Pen15Palette.Cyan,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.4.sp),
            )
        }
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = "Settings",
            tint = Pen15Palette.TextSecondary,
            modifier = Modifier
                .size(28.dp)
                .clickable { /* TODO settings */ },
        )
    }
}

@Composable
private fun EngagementBar(clientName: String?, onClick: () -> Unit) {
    val active = clientName != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    0f to (if (active) Pen15Palette.Lime else Pen15Palette.Crimson)
                        .copy(alpha = 0.18f),
                    1f to Color.Transparent,
                )
            )
            .background(Pen15Palette.SurfaceLow.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = (if (active) Pen15Palette.Lime else Pen15Palette.Crimson)
                    .copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = null,
            tint = if (active) Pen15Palette.Lime else Pen15Palette.Crimson,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (active) "Mission active" else "No mission",
                color = if (active) Pen15Palette.Lime else Pen15Palette.Crimson,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                clientName ?: "Tap to add the client and scope before you start.",
                color = Pen15Palette.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            if (active) "VIEW" else "ADD",
            color = Pen15Palette.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HardwareRow(state: ConnectionState) {
    val flipperChip = when {
        state.flipperReady -> ChipState.Connected
        state is ConnectionState.Searching -> ChipState.Searching
        else -> ChipState.Disconnected
    }
    val awokChip = when {
        state.awokReady -> ChipState.Connected
        state is ConnectionState.Searching -> ChipState.Searching
        else -> ChipState.Disconnected
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        HardwareChip(
            label = "Flipper Zero",
            sublabel = if (flipperChip == ChipState.Connected) "Pen15 controller running" else "Plug into USB-C",
            state = flipperChip,
            accent = Pen15Palette.Cyan,
            icon = Icons.Rounded.DeveloperBoard,
            onClick = { /* future: connection help */ },
        )
        Spacer(Modifier.height(10.dp))
        HardwareChip(
            label = "AWOK Mini v3",
            sublabel = if (awokChip == ChipState.Connected) "WiFi attacks ready" else "Plug into Flipper or hub",
            state = awokChip,
            accent = Pen15Palette.Magenta,
            icon = Icons.Rounded.Wifi,
            onClick = { /* future */ },
        )
    }
}

@Composable
private fun Pen15Sections(nav: NavController) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        // Big tile grid: 2x2
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MissionTile(
                title = "Flipper",
                subtitle = "RFID, NFC, Sub-GHz, IR, BadUSB, GPIO",
                icon = Icons.Rounded.DeveloperBoard,
                accent = Pen15Palette.Cyan,
                onClick = { nav.navigate(Routes.FLIPPER) },
                modifier = Modifier.weight(1f),
            )
            MissionTile(
                title = "WiFi",
                subtitle = "Scan, deauth, evil portal, MITM",
                icon = Icons.Rounded.Wifi,
                accent = Pen15Palette.Magenta,
                onClick = { nav.navigate(Routes.WIFI) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MissionTile(
                title = "Crack",
                subtitle = "Handshakes, hashes, wordlists",
                icon = Icons.Rounded.Bolt,
                accent = Pen15Palette.Lime,
                onClick = { nav.navigate(Routes.CRACK) },
                modifier = Modifier.weight(1f),
            )
            MissionTile(
                title = "Recon",
                subtitle = "OSINT, nmap, dorks, sensors",
                icon = Icons.Rounded.Public,
                accent = Pen15Palette.Violet,
                onClick = { nav.navigate(Routes.RECON) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LegalFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = null,
            tint = Pen15Palette.TextDim,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "Only test hardware and networks you own, or that a client has authorized you in writing to test. Pen15 logs every action under the active mission.",
            color = Pen15Palette.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
