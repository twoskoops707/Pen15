package com.pen15.ui.flipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ContactlessOutlined
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Sensors
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
fun FlipperHomeScreen(nav: NavController) {
    val state by ConnectionService.state.collectAsState()
    val ready = state.flipperReady
    Pen15Background {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Flipper",
                subtitle = if (ready) "Ready. Pick a tool." else "Plug in your Flipper and launch Pen15 Controller.",
                onBack = { nav.popBackStack() },
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("RFID", "125 kHz read / emulate / brute",
                            Icons.Rounded.ContactlessOutlined, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_RFID) }, Modifier.weight(1f))
                        MissionTile("NFC", "13.56 MHz read / write / emulate",
                            Icons.Rounded.Sensors, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_NFC) }, Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("Sub-GHz", "Listen, replay, brute, jam",
                            Icons.Rounded.GraphicEq, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_SUBGHZ) }, Modifier.weight(1f))
                        MissionTile("Infrared", "Learn, blast, TV-B-Gone",
                            Icons.Rounded.NetworkCell, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_IR) }, Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("iButton", "Read / emulate / brute",
                            Icons.Rounded.Key, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_IBUTTON) }, Modifier.weight(1f))
                        MissionTile("Bad USB", "DuckyScript runner",
                            Icons.Rounded.Keyboard, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_BADUSB) }, Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MissionTile("GPIO", "Pin grid, UART, I2C scan",
                            Icons.Rounded.Dns, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_GPIO) }, Modifier.weight(1f))
                        MissionTile("Bluetooth", "BLE scan / spoof",
                            Icons.Rounded.BluetoothSearching, Pen15Palette.Cyan, ready,
                            { nav.navigate(Routes.FLIPPER_BT) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
