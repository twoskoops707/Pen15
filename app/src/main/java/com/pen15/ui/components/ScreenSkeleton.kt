package com.pen15.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pen15.ui.theme.Pen15Palette

/**
 * Reusable scaffold for every operational screen:
 *   - Hero status word at top.
 *   - Plain-language subtitle.
 *   - Optional list of result rows / details.
 *   - Single big primary action at the bottom.
 *
 * @param accent screen accent color (Cyan for Flipper, Magenta for AWOK,
 *               Lime for crack, Violet for recon).
 */
@Composable
fun ScreenSkeleton(
    nav: NavController,
    title: String,
    statusWord: String,
    subtitle: String,
    accent: Color,
    primaryAction: @Composable () -> Unit,
    onHelp: (() -> Unit)? = null,
    pulsing: Boolean = false,
    body: @Composable () -> Unit = {},
) {
    Pen15Background {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = title,
                onBack = { nav.popBackStack() },
                onHelp = onHelp,
            )
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        StatusHero(
                            word = statusWord,
                            accent = accent,
                            subtitle = subtitle,
                            pulsing = pulsing,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { body() } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            primaryAction()
            Spacer(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(20.dp)
            )
        }
    }
}
