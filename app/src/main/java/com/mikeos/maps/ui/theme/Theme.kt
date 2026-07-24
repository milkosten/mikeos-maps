package com.mikeos.maps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MikeOS palette — matches the daemon's dark UI + #5aa4ff accent.
val MikeAccent = Color(0xFF5AA4FF)
val MikeBg = Color(0xFF0D1117)
val MikeSurface = Color(0xFF161B22)
val MikeSurfaceVariant = Color(0xFF21262D)
val MikeOnSurface = Color(0xFFE6EDF3)
val MikeMuted = Color(0xFF8B949E)
val MikeGreen = Color(0xFF3FB950)
val MikeRed = Color(0xFFF85149)

private val MikeDarkColors = darkColorScheme(
    primary = MikeAccent,
    onPrimary = Color(0xFF0A0E14),
    secondary = MikeAccent,
    background = MikeBg,
    onBackground = MikeOnSurface,
    surface = MikeSurface,
    onSurface = MikeOnSurface,
    surfaceVariant = MikeSurfaceVariant,
    onSurfaceVariant = MikeMuted,
    error = MikeRed,
)

@Composable
fun MikeOsTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MikeDarkColors,
        typography = Typography(),
        content = content,
    )
}
