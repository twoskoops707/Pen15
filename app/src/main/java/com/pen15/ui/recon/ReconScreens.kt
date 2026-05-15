package com.pen15.ui.recon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.ui.components.Pen15Background
import com.pen15.ui.components.PlainText
import com.pen15.ui.components.SectionLabel
import com.pen15.ui.components.ScreenHeader
import com.pen15.ui.theme.Pen15Palette

@Composable
fun ReconHomeScreen(nav: NavController) {
    Pen15Background {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Recon",
                subtitle = "OSINT, network scan, dorks, phone sensors",
                onBack = { nav.popBackStack() },
            )
            SectionLabel("COMING NEXT", Pen15Palette.Violet, modifier = Modifier.padding(top = 12.dp))
            PlainText(
                "OSINT, nmap, Google Dork, and phone sensors land in the next commit. Their architecture is in docs/DESIGN_V4.md §6.4.",
                modifier = Modifier.padding(top = 8.dp),
                accent = Pen15Palette.TextSecondary,
            )
        }
    }
}
