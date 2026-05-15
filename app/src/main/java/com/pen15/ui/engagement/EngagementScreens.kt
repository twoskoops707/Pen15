package com.pen15.ui.engagement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.domain.engagement.EngagementRepository
import com.pen15.domain.engagement.Scope
import com.pen15.ui.Routes
import com.pen15.ui.components.Pen15Background
import com.pen15.ui.components.PrimaryAction
import com.pen15.ui.components.PrimaryActionState
import com.pen15.ui.components.SectionLabel
import com.pen15.ui.components.ScreenHeader
import com.pen15.ui.theme.Pen15Palette

@Composable
fun EngagementListScreen(nav: NavController) {
    val all by EngagementRepository.all.collectAsState()
    val active by EngagementRepository.active.collectAsState()
    Pen15Background {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Missions",
                subtitle = "${all.size} on file",
                onBack = { nav.popBackStack() },
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                item { SectionLabel("ACTIVE", Pen15Palette.Lime) }
                item { Spacer(Modifier.height(8.dp)) }
                if (active != null) {
                    item {
                        EngagementRow(
                            client = active!!.clientName,
                            sub = "Active. Tap to end.",
                            accent = Pen15Palette.Lime,
                        ) {
                            EngagementRepository.endActive()
                        }
                    }
                } else {
                    item {
                        Text(
                            "No active mission.",
                            color = Pen15Palette.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
                item { SectionLabel("ALL", Pen15Palette.TextSecondary) }
                item { Spacer(Modifier.height(8.dp)) }
                items(count = all.size) { i ->
                    val e = all[i]
                    EngagementRow(
                        client = e.clientName,
                        sub = if (e.active) "Active" else "Ended",
                        accent = if (e.active) Pen15Palette.Lime else Pen15Palette.TextSecondary,
                    ) {
                        if (!e.active) EngagementRepository.setActive(e.id)
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
                item {
                    PrimaryAction(
                        label = "NEW MISSION",
                        state = PrimaryActionState.Ready,
                        onClick = { nav.navigate(Routes.ENGAGEMENT_NEW) },
                    )
                }
            }
        }
    }
}

@Composable
fun EngagementWizardScreen(nav: NavController) {
    var client by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf("") }
    var ssids by remember { mutableStateOf("") }
    var bssids by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf("") }
    var ipranges by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("7") }

    val canSubmit = client.isNotBlank() && operator.isNotBlank() && auth

    Pen15Background {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "New mission",
                subtitle = "Tell Pen15 who you're testing for and what's in scope.",
                onBack = { nav.popBackStack() },
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionLabel("CLIENT", Pen15Palette.Cyan) }
                item { LabeledField("Client name", client) { client = it } }
                item { LabeledField("Your name", operator) { operator = it } }
                item { LabeledField("Mission length (days)", days, KeyboardType.Number) { days = it } }
                item { Spacer(Modifier.height(16.dp)) }
                item { SectionLabel("WHAT'S IN SCOPE", Pen15Palette.Magenta) }
                item { LabeledField("WiFi SSIDs (one per line)", ssids, multiLine = true) { ssids = it } }
                item { LabeledField("BSSID prefixes / MACs", bssids, multiLine = true) { bssids = it } }
                item { LabeledField("Domains", domains, multiLine = true) { domains = it } }
                item { LabeledField("IP ranges (CIDR)", ipranges, multiLine = true) { ipranges = it } }
                item { LabeledField("Notes / SOW summary", notes, multiLine = true) { notes = it } }
                item { Spacer(Modifier.height(16.dp)) }
                item { SectionLabel("SIGN-OFF", Pen15Palette.Lime) }
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = auth, onCheckedChange = { auth = it })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "I have written authorization from the client to test the items above.",
                            color = Pen15Palette.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
                item {
                    PrimaryAction(
                        label = "START MISSION",
                        state = if (canSubmit) PrimaryActionState.Ready else PrimaryActionState.Disabled,
                        onClick = onClick@{
                            if (!canSubmit) return@onClick
                            EngagementRepository.create(
                                clientName = client.trim(),
                                operatorName = operator.trim(),
                                scope = Scope(
                                    ssids = ssids.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    bssidPrefixes = bssids.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    domains = domains.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    ipRanges = ipranges.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                ),
                                notes = notes,
                                durationDays = days.toIntOrNull() ?: 7,
                            )
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun EngagementRow(
    client: String,
    sub: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Pen15Palette.SurfaceLow.copy(alpha = 0.55f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(client, color = Pen15Palette.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = Pen15Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    multiLine: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Pen15Palette.TextSecondary) },
        singleLine = !multiLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    )
}
