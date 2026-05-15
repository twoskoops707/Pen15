package com.pen15.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pen15.ui.theme.Pen15Palette

enum class ChipState { Disconnected, Searching, Connected }

@Composable
fun HardwareChip(
    label: String,
    sublabel: String,
    state: ChipState,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.DeveloperBoard,
) {
    val tint = when (state) {
        ChipState.Disconnected -> Pen15Palette.TextDim
        ChipState.Searching    -> Pen15Palette.Amber
        ChipState.Connected    -> accent
    }
    val animTint by animateColorAsState(targetValue = tint, animationSpec = tween(280), label = "tint")

    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == ChipState.Connected) {
            pulse.animateTo(
                1f,
                infiniteRepeatable(
                    animation = tween(1800),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else if (state == ChipState.Searching) {
            pulse.animateTo(
                1f,
                infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else {
            pulse.snapTo(0f)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    0f to accent.copy(alpha = if (state == ChipState.Connected) 0.10f else 0.04f),
                    1f to Color.Transparent,
                )
            )
            .background(Pen15Palette.SurfaceLow.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = if (state == ChipState.Connected) animTint.copy(alpha = 0.55f) else Pen15Palette.Outline,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon halo
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(animTint.copy(alpha = 0.16f))
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to animTint.copy(alpha = 0.4f * pulse.value),
                            1f to Color.Transparent,
                        ),
                        radius = size.minDimension * 0.9f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = Pen15Palette.TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                ),
            )
            Text(
                text = sublabel,
                color = Pen15Palette.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(animTint)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to animTint.copy(alpha = 0.6f),
                            1f to Color.Transparent,
                        ),
                        radius = size.minDimension * 1.6f,
                    )
                },
        )
    }
}
