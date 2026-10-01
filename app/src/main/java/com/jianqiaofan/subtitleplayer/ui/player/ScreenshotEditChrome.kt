package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.ui.graphics.Color

enum class ScreenshotEditThemeKind {
    /** Current default: dark panel, light text. */
    Dim,
    /** Light panel, dark text — easier to separate from dark video frames. */
    Bright,
    ;

    companion object {
        fun fromWire(raw: String?): ScreenshotEditThemeKind =
            if (raw.equals("bright", ignoreCase = true)) Bright else Dim
    }

    fun toWire(): String = when (this) {
        Dim -> "dim"
        Bright -> "bright"
    }
}

data class ScreenshotEditChrome(
    val kind: ScreenshotEditThemeKind = ScreenshotEditThemeKind.Dim,
    val opacity: Float = DEFAULT_OPACITY,
) {
    val clamped: ScreenshotEditChrome
        get() = copy(opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY))

    val panel: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color(0xFF1A1A1A).copy(alpha = opacity)
            ScreenshotEditThemeKind.Bright -> Color(0xFFF2F2F6).copy(alpha = opacity)
        }

    val field: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color.Black.copy(alpha = (opacity * 0.75f).coerceIn(0.15f, 0.9f))
            ScreenshotEditThemeKind.Bright -> Color.White.copy(alpha = (opacity + 0.12f).coerceIn(0.35f, 1f))
        }

    val text: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color.White
            ScreenshotEditThemeKind.Bright -> Color(0xFF1C1C22)
        }

    val muted: Color
        get() = text.copy(alpha = 0.55f)

    val border: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color(0xFF5A5A5A)
            ScreenshotEditThemeKind.Bright -> Color(0xFFA8A8B0)
        }

    val button: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color(0xFF2B2B2B)
            ScreenshotEditThemeKind.Bright -> Color(0xFFE4E4EA)
        }

    val buttonDisabled: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color(0xFF222222)
            ScreenshotEditThemeKind.Bright -> Color(0xFFD0D0D6)
        }

    val borderDisabled: Color
        get() = when (kind) {
            ScreenshotEditThemeKind.Dim -> Color(0xFF3A3A3A)
            ScreenshotEditThemeKind.Bright -> Color(0xFFC0C0C8)
        }

    companion object {
        const val DEFAULT_OPACITY = 0.80f
        const val MIN_OPACITY = 0.20f
        const val MAX_OPACITY = 0.95f
        val Default = ScreenshotEditChrome()
    }
}
