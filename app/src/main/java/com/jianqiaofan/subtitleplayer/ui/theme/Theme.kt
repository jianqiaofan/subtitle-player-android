package com.jianqiaofan.subtitleplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = AccentPurple,
    onPrimary = Color(0xFF1A1A1A),
    secondary = AccentPurple,
    onSecondary = Color(0xFF1A1A1A),
    background = WindowBackground,
    onBackground = OnDark,
    surface = SurfacePanel,
    onSurface = OnDark,
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = OnDarkMuted,
    outline = Color(0xFF5A5A5A),
)

@Composable
fun SubtitlePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
