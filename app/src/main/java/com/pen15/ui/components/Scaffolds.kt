package com.pen15.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pen15.ui.theme.Pen15Palette

@Composable
fun Pen15Background(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pen15Palette.HeroBg),
    ) {
        content()
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Pen15Palette.SurfaceLow.copy(alpha = 0.7f)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Pen15Palette.TextPrimary,
                )
            }
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                color = Pen15Palette.TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.6.sp),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Pen15Palette.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (onHelp != null) {
            IconButton(
                onClick = onHelp,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Pen15Palette.SurfaceLow.copy(alpha = 0.7f)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.HelpOutline,
                    contentDescription = "Help",
                    tint = Pen15Palette.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun PlainText(
    text: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = Pen15Palette.TextPrimary,
) {
    Text(
        text = text,
        color = accent,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(horizontal = 24.dp),
    )
}

@Composable
fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.8.sp),
        modifier = modifier.padding(horizontal = 24.dp),
    )
}
