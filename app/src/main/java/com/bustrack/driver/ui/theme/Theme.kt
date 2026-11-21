package com.bustrack.driver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Core Neon Palette ──
val NeonGreen = Color(0xFF00FF87)
val NeonCyan = Color(0xFF00E5FF)
val NeonBlue = Color(0xFF448AFF)
val NeonPurple = Color(0xFFBB86FC)
val NeonRed = Color(0xFFFF3B5C)
val NeonYellow = Color(0xFFFFD60A)
val NeonOrange = Color(0xFFFF9100)

// ── Surface, Cards & Glassmorphism ──
val SurfaceDark = Color(0xFF080C12)
val CardDark = Color(0xFF10151F)
val CardElevated = Color(0xFF161E2C)
val CardBorder = Color(0xFF222C3D)
val AccentGlow = Color(0x2600E5FF)
val GreenGlow = Color(0x2600FF87)

// ── Typography Colors ──
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF94A3B8)
val TextDim = Color(0xFF64748B)
val TextMuted = Color(0xFF475569)

private val BusTrackColorScheme = darkColorScheme(
    primary = NeonCyan,
    secondary = NeonGreen,
    tertiary = NeonPurple,
    error = NeonRed,
    background = Color(0xFF04060A),
    surface = Color(0xFF080C12),
    surfaceVariant = CardDark,
    onBackground = Color.White,
    onSurface = Color.White,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    outline = CardBorder
)

/**
 * Premium dark-only Material 3 theme optimized for OLED displays and high-contrast telemetry.
 */
@Composable
fun BusTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BusTrackColorScheme,
        content = content
    )
}
