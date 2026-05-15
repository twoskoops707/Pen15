package com.pen15.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Pen15 v4 palette. One source of truth for every color.
 *
 * Visual identity:
 *   - Pure black canvas (#000000) for app store screenshot pop
 *   - Electric cyan = Flipper / hardware
 *   - Hot magenta  = AWOK / WiFi attacks
 *   - Lime green   = ready / safe / success
 *   - Amber        = caution / standby
 *   - Crimson      = denied / error / out-of-scope
 */
object Pen15Palette {
    // Background / surfaces
    val Black            = Color(0xFF000000)
    val Ink              = Color(0xFF06070C)   // top-of-screen tint
    val Slate            = Color(0xFF0E1018)
    val SurfaceLow       = Color(0xFF11141C)
    val SurfaceHigh      = Color(0xFF181B26)
    val Outline          = Color(0xFF22273A)
    val OutlineBright    = Color(0xFF323958)

    // Text
    val TextPrimary      = Color(0xFFF6F8FF)
    val TextSecondary    = Color(0xFFA3A9C2)
    val TextDim          = Color(0xFF5C6383)
    val TextHint         = Color(0xFF393F58)

    // Brand / status
    val Cyan             = Color(0xFF00E5FF)   // Flipper
    val CyanGlow         = Color(0xFF6CF3FF)
    val Magenta          = Color(0xFFFF2D6F)   // AWOK
    val MagentaGlow      = Color(0xFFFF7AA8)
    val Lime             = Color(0xFF39FF14)   // success / ready
    val LimeGlow         = Color(0xFF8AFF6F)
    val Amber            = Color(0xFFFFB020)   // standby / warning
    val Crimson          = Color(0xFFFF3344)   // error / out-of-scope
    val Violet           = Color(0xFF7A5BFF)   // recon / OSINT
    val Aqua             = Color(0xFF14E0C9)   // crack / linux

    // Hero gradients
    val HeroBg = Brush.verticalGradient(
        0f    to Color(0xFF0A0E18),
        0.45f to Color(0xFF05060B),
        1f    to Color(0xFF000000),
    )

    fun glow(c: Color, alpha: Float = 0.18f): Brush =
        Brush.radialGradient(
            0f to c.copy(alpha = alpha),
            1f to Color.Transparent,
        )

    fun chipGradient(c: Color): Brush = Brush.linearGradient(
        0f to c.copy(alpha = 0.14f),
        1f to Color.Transparent,
    )
}
