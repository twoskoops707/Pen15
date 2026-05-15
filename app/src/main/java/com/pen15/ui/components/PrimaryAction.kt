package com.pen15.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pen15.ui.theme.Pen15Palette

enum class PrimaryActionState { Ready, Working, Disabled, Danger, Success }

/**
 * The single largest action on a screen. Always at the bottom, always
 * a thumb-friendly target. Color and copy change with state.
 */
@Composable
fun PrimaryAction(
    label: String,
    state: PrimaryActionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Rounded.PlayArrow,
    secondaryLabel: String? = null,
) {
    val (bg, fg, glow) = when (state) {
        PrimaryActionState.Ready    -> Triple(Pen15Palette.Lime,        Pen15Palette.Black,       Pen15Palette.LimeGlow)
        PrimaryActionState.Working  -> Triple(Pen15Palette.Amber,       Pen15Palette.Black,       Pen15Palette.Amber)
        PrimaryActionState.Disabled -> Triple(Pen15Palette.SurfaceHigh, Pen15Palette.TextDim,     Color.Transparent)
        PrimaryActionState.Danger   -> Triple(Pen15Palette.Crimson,     Pen15Palette.TextPrimary, Pen15Palette.Magenta)
        PrimaryActionState.Success  -> Triple(Pen15Palette.Lime,        Pen15Palette.Black,       Pen15Palette.LimeGlow)
    }
    val animBg by animateColorAsState(targetValue = bg, animationSpec = tween(220), label = "bg")
    val animFg by animateColorAsState(targetValue = fg, animationSpec = tween(220), label = "fg")

    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == PrimaryActionState.Working || state == PrimaryActionState.Danger) {
            pulse.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else {
            pulse.snapTo(0f)
        }
    }

    val enabled = state != PrimaryActionState.Disabled

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .padding(horizontal = 20.dp)
            .drawBehind {
                if (glow != Color.Transparent) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to glow.copy(alpha = 0.32f + pulse.value * 0.18f),
                            1f to Color.Transparent,
                        ),
                        radius = size.width * 0.6f,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clip(MaterialTheme.shapes.large)
                .background(animBg)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.18f),
                        1f to Color.White.copy(alpha = 0.02f),
                    ),
                    shape = MaterialTheme.shapes.large,
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = animFg,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label.uppercase(),
                        color = animFg,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    if (secondaryLabel != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = secondaryLabel,
                            color = animFg.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
