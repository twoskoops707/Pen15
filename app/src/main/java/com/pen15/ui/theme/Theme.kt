package com.pen15.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary            = Pen15Palette.Cyan,
    onPrimary          = Pen15Palette.Black,
    primaryContainer   = Pen15Palette.SurfaceHigh,
    onPrimaryContainer = Pen15Palette.CyanGlow,
    secondary          = Pen15Palette.Magenta,
    onSecondary        = Pen15Palette.Black,
    secondaryContainer = Pen15Palette.SurfaceHigh,
    onSecondaryContainer = Pen15Palette.MagentaGlow,
    tertiary           = Pen15Palette.Lime,
    onTertiary         = Pen15Palette.Black,
    background         = Pen15Palette.Black,
    onBackground       = Pen15Palette.TextPrimary,
    surface            = Pen15Palette.Slate,
    onSurface          = Pen15Palette.TextPrimary,
    surfaceVariant     = Pen15Palette.SurfaceHigh,
    onSurfaceVariant   = Pen15Palette.TextSecondary,
    surfaceContainerLowest = Pen15Palette.Black,
    surfaceContainerLow    = Pen15Palette.Ink,
    surfaceContainer       = Pen15Palette.SurfaceLow,
    surfaceContainerHigh   = Pen15Palette.SurfaceHigh,
    surfaceContainerHighest = Pen15Palette.SurfaceHigh,
    outline            = Pen15Palette.Outline,
    outlineVariant     = Pen15Palette.OutlineBright,
    error              = Pen15Palette.Crimson,
    onError            = Pen15Palette.TextPrimary,
)

private val Pen15Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(18.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun Pen15Theme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Pen15Palette.Black.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = Pen15Typography,
        shapes      = Pen15Shapes,
        content     = content,
    )
}
