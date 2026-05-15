package com.pen15.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pen15.ui.theme.Pen15Palette

/**
 * The big single-word status ("READY", "SCANNING", "ATTACKING") that
 * dominates every operational screen. Has a soft animated glow underneath.
 */
@Composable
fun StatusHero(
    word: String,
    accent: Color,
    subtitle: String,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulsing) {
        if (pulsing) {
            pulse.animateTo(
                1f,
                infiniteRepeatable(
                    animation = tween(1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else {
            pulse.snapTo(0f)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to accent.copy(alpha = 0.16f + pulse.value * 0.12f),
                        1f to Color.Transparent,
                    ),
                    radius = size.minDimension * 0.65f,
                    center = Offset(size.width / 2f, size.height / 2f + 24f),
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = word.uppercase(),
            color = accent,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = Pen15Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
