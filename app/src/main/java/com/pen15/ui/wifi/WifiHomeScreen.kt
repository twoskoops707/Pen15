package com.pen15.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Diversity1
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.domain.connection.ConnectionService
import com.pen15.ui.Routes
import com.pen15.ui.components.MissionTile
import com.pen15.ui.components.Pen15Background
import com.pen15.ui.components.ScreenHeader
import com.pen15.ui.theme.Pen15Palette

@Composable
fun WifiHomeScreen(nav: NavController) {
    val state by ConnectionService.state.collectAsState()
    val ready = state.awokReady || state.flipperReady // bridge mode also ok
    Pen15Background {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "WiFi",
                subtitle = if (ready) "Pick an attack." else "Plug in your AWOK.",
                onBack = { nav.popBackStack() },
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionTile("Scan", "List nearby networks",
                        Icons.Rounded.Wifi, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_SCAN) }, Modifier.weight(1f))
                    MissionTile("Deauth", "Boot devices off a network",
                        Icons.Rounded.WifiOff, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_DEAUTH) }, Modifier.weight(1f))
                } }
                item { Spacer(Modifier.height(12.dp)) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionTile("PMKID", "Capture WPA handshake",
                        Icons.Rounded.Bolt, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_PMKID) }, Modifier.weight(1f))
                    MissionTile("Evil portal", "Captive page",
                        Icons.Rounded.SettingsRemote, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_EVIL) }, Modifier.weight(1f))
                } }
                item { Spacer(Modifier.height(12.dp)) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionTile("Beacon spam", "Flood SSIDs",
                        Icons.Rounded.Campaign, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_BEACON) }, Modifier.weight(1f))
                    MissionTile("Karma", "Probe responder",
                        Icons.Rounded.Diversity1, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_KARMA) }, Modifier.weight(1f))
                } }
                item { Spacer(Modifier.height(12.dp)) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionTile("Capture", "Save .pcap",
                        Icons.Rounded.Save, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_PCAP) }, Modifier.weight(1f))
                    MissionTile("MITM", "Man-in-the-middle helper",
                        Icons.Rounded.Hub, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_MITM) }, Modifier.weight(1f))
                } }
                item { Spacer(Modifier.height(12.dp)) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionTile("BLE spam", "Sour-apple etc.",
                        Icons.Rounded.BluetoothAudio, Pen15Palette.Magenta, ready,
                        { nav.navigate(Routes.WIFI_BLE_SPAM) }, Modifier.weight(1f))
                    MissionTile("Probe sniff", "Track devices",
                        Icons.Rounded.SearchOff, Pen15Palette.Magenta, ready,
                        { /* TODO probe */ }, Modifier.weight(1f))
                } }
            }
        }
    }
}
