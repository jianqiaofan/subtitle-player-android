package com.jianqiaofan.subtitleplayer.domain.display

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue

enum class OnScreenSubtitlePosition {
    Top,
    Middle,
    Bottom,
    ;

    val wire: String
        get() = when (this) {
            Top -> "top"
            Middle -> "middle"
            Bottom -> "bottom"
        }

    companion object {
        fun fromWire(raw: String?): OnScreenSubtitlePosition =
            when (raw?.trim()?.lowercase()) {
                "top" -> Top
                "middle" -> Middle
                else -> Bottom
            }
    }
}

enum class ImmersiveListSideLandscape {
    Left,
    Right,
    ;

    val wire: String
        get() = when (this) {
            Left -> "left"
            Right -> "right"
        }

    companion object {
        fun fromWire(raw: String?): ImmersiveListSideLandscape =
            when (raw?.trim()?.lowercase()) {
                "left" -> Left
                else -> Right
            }
    }
}

enum class ImmersiveListSidePortrait {
    Top,
    Bottom,
    ;

    val wire: String
        get() = when (this) {
            Top -> "top"
            Bottom -> "bottom"
        }

    companion object {
        fun fromWire(raw: String?): ImmersiveListSidePortrait =
            when (raw?.trim()?.lowercase()) {
                "top" -> Top
                else -> Bottom
            }
    }
}

enum class SubtitleListDensity {
    Normal,
    Compact,
    ;

    val wire: String
        get() = when (this) {
            Normal -> "normal"
            Compact -> "compact"
        }

    val label: String
        get() = when (this) {
            Normal -> "普通"
            Compact -> "紧凑"
        }

    fun toggled(): SubtitleListDensity =
        when (this) {
            Normal -> Compact
            Compact -> Normal
        }

    companion object {
        fun fromWire(raw: String?): SubtitleListDensity =
            when (raw?.trim()?.lowercase()) {
                "compact" -> Compact
                else -> Normal
            }
    }
}

enum class PreferredOrientation {
    System,
    Landscape,
    Portrait,
    ;

    val wire: String
        get() = when (this) {
            System -> "system"
            Landscape -> "landscape"
            Portrait -> "portrait"
        }

    companion object {
        fun fromWire(raw: String?): PreferredOrientation =
            when (raw?.trim()?.lowercase()) {
                "landscape" -> Landscape
                "portrait" -> Portrait
                else -> System
            }
    }
}

/**
 * Whether the media picture itself is portrait (height > width).
 * Returns null when size is unknown or square (e.g. audio / not yet ready).
 */
fun videoPictureIsPortrait(videoWidth: Int, videoHeight: Int): Boolean? {
    if (videoWidth <= 0 || videoHeight <= 0) return null
    return when {
        videoHeight > videoWidth -> true
        videoWidth > videoHeight -> false
        else -> null
    }
}

/**
 * Immersive list is only valid when picture orientation matches device orientation.
 * Unknown / square picture is treated as landscape content (common for audio placeholder).
 */
fun isImmersiveListAvailable(
    videoWidth: Int,
    videoHeight: Int,
    devicePortrait: Boolean,
): Boolean {
    val picturePortrait = videoPictureIsPortrait(videoWidth, videoHeight) ?: false
    return picturePortrait == devicePortrait
}

fun immersiveUnavailableReason(
    videoWidth: Int,
    videoHeight: Int,
    devicePortrait: Boolean,
): String? {
    if (isImmersiveListAvailable(videoWidth, videoHeight, devicePortrait)) return null
    return "当前画面方向与屏幕方向不一致，无法使用沉浸列表"
}

data class PlayerDisplaySettings(
    val onscreenEnabled: Boolean = true,
    val onscreenFontSize: Int = 18,
    val onscreenColor: String = "#FFFFFF",
    val onscreenBgOpacity: Float = 0.55f,
    val onscreenWidthPercent: Int = 80,
    val onscreenPosition: OnScreenSubtitlePosition = OnScreenSubtitlePosition.Bottom,
    val immersiveList: Boolean = false,
    val immersiveListOpacity: Float = 0.28f,
    val immersiveListSideLandscape: ImmersiveListSideLandscape = ImmersiveListSideLandscape.Right,
    val immersiveListSidePortrait: ImmersiveListSidePortrait = ImmersiveListSidePortrait.Bottom,
    val immersiveListSizePercent: Int = 36,
    val subtitleListDensity: SubtitleListDensity = SubtitleListDensity.Normal,
    val preferredOrientation: PreferredOrientation = PreferredOrientation.System,
) {
    fun clamp(): PlayerDisplaySettings = copy(
        onscreenFontSize = onscreenFontSize.coerceIn(12, 72),
        onscreenColor = normalizeHexColor(onscreenColor),
        onscreenBgOpacity = onscreenBgOpacity.coerceIn(0f, 1f),
        onscreenWidthPercent = onscreenWidthPercent.coerceIn(30, 100),
        immersiveListOpacity = immersiveListOpacity.coerceIn(0f, 1f),
        immersiveListSizePercent = immersiveListSizePercent.coerceIn(18, 70),
    )
}

fun normalizeHexColor(raw: String): String {
    var value = raw.trim()
    if (value.isEmpty()) return "#FFFFFF"
    if (!value.startsWith("#")) value = "#$value"
    val hex = value.drop(1)
    return when (hex.length) {
        6, 8 -> "#${hex.uppercase()}"
        else -> "#FFFFFF"
    }
}

fun parseColorArgb(hex: String, fallback: Long = 0xFFFFFFFF): Long {
    val normalized = normalizeHexColor(hex).removePrefix("#")
    return try {
        when (normalized.length) {
            6 -> (0xFF000000L or normalized.toLong(16))
            8 -> normalized.toLong(16)
            else -> fallback
        }
    } catch (_: NumberFormatException) {
        fallback
    }
}

/** Compact list line: body only; keep original newlines for multi-line display. */
fun formatCueListBody(cue: SubtitleCue): String = cue.text

fun joinSelectedCueTexts(cues: List<SubtitleCue>, indices: Collection<Int>): String =
    indices.sorted()
        .mapNotNull { cues.getOrNull(it)?.text }
        .joinToString("\n")
