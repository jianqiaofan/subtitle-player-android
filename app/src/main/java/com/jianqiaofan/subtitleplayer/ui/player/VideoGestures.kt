package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ScreenHalf { Left, Right }

/**
 * Bilibili-style horizontal seek sensitivity.
 * Decided by where the finger first touched: top third = fine, middle = normal, bottom = coarse.
 * Ratios follow the common Bilibili touch model (0.05 : 0.2 : 1).
 */
enum class ProgressSeekMode { Slow, Medium, Fast }

data class SeekDragSession(val anchorMs: Long, val durationMs: Long)

const val PLAYBACK_BRIGHTNESS_FLOOR = 0.01f

/** Medium-zone full-width swipe ≈ 90s, matching common Bilibili-style players. */
internal const val SEEK_MEDIUM_SECONDS_PER_WIDTH = 90f

fun screenHalf(x: Float, width: Float): ScreenHalf =
    if (width > 0f && x >= width / 2f) ScreenHalf.Right else ScreenHalf.Left

/** Finger moving up (negative Y) raises the level. A full-height drag covers the whole range. */
fun levelAfterVerticalDrag(startLevel: Float, dragDistanceY: Float, span: Float): Float {
    if (span <= 0f) return startLevel.coerceIn(0f, 1f)
    return (startLevel - dragDistanceY / span).coerceIn(0f, 1f)
}

fun verticalDragExceededSlop(totalX: Float, totalY: Float, touchSlop: Float): Boolean =
    abs(totalY) > touchSlop && abs(totalY) > abs(totalX)

fun horizontalDragExceededSlop(totalX: Float, totalY: Float, touchSlop: Float): Boolean =
    abs(totalX) > touchSlop && abs(totalX) >= abs(totalY)

fun volumeIndexForLevel(level: Float, maxVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return (level.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
}

fun playbackBrightness(level: Float): Float = level.coerceIn(PLAYBACK_BRIGHTNESS_FLOOR, 1f)

fun levelPercent(level: Float): Int = (level.coerceIn(0f, 1f) * 100f).roundToInt()

fun progressSeekModeForStartY(startY: Float, height: Float): ProgressSeekMode {
    if (height <= 0f) return ProgressSeekMode.Medium
    val ratio = (startY / height).coerceIn(0f, 1f)
    return when {
        ratio < 1f / 3f -> ProgressSeekMode.Slow
        ratio < 2f / 3f -> ProgressSeekMode.Medium
        else -> ProgressSeekMode.Fast
    }
}

fun secondsPerWidthForSeekMode(mode: ProgressSeekMode): Float = when (mode) {
    ProgressSeekMode.Slow -> SEEK_MEDIUM_SECONDS_PER_WIDTH * (0.05f / 0.2f)
    ProgressSeekMode.Medium -> SEEK_MEDIUM_SECONDS_PER_WIDTH
    ProgressSeekMode.Fast -> SEEK_MEDIUM_SECONDS_PER_WIDTH * (1f / 0.2f)
}

/** Right swipe (positive [totalDx]) seeks forward; left seeks backward. */
fun seekTargetMs(
    anchorMs: Long,
    totalDx: Float,
    widthPx: Float,
    durationMs: Long,
    mode: ProgressSeekMode,
): Long {
    val end = durationMs.coerceAtLeast(0L)
    val start = anchorMs.coerceIn(0L, end)
    if (widthPx <= 0f || end <= 0L) return start
    val deltaMs = (totalDx / widthPx) * secondsPerWidthForSeekMode(mode) * 1000.0
    return (start + deltaMs).toLong().coerceIn(0L, end)
}

fun formatSeekHud(previewMs: Long, durationMs: Long, anchorMs: Long): String {
    val base = "${formatClock(previewMs / 1000.0)} / ${formatClock(durationMs.coerceAtLeast(0L) / 1000.0)}"
    val deltaSec = ((previewMs - anchorMs) / 1000.0).roundToInt()
    if (deltaSec == 0) return base
    val deltaLabel = if (deltaSec > 0) "+${deltaSec}秒" else "${deltaSec}秒"
    return "$base  ($deltaLabel)"
}

internal fun subtitleFollowSuspended(playing: Boolean, menuOpen: Boolean, selecting: Boolean): Boolean =
    !playing || menuOpen || selecting

suspend fun PointerInputScope.detectVideoViewportGestures(
    canPan: () -> Boolean,
    onTap: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onLevelDragStart: (ScreenHalf) -> Float,
    onLevelDrag: (ScreenHalf, Float, Float, Float) -> Unit,
    onLevelDragEnd: () -> Unit,
    onSeekDragStart: (ProgressSeekMode) -> SeekDragSession,
    onSeekDrag: (previewMs: Long) -> Unit,
    onSeekDragEnd: (targetMs: Long) -> Unit,
    onSeekDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val side = screenHalf(down.position.x, width)
        val touchSlop = viewConfiguration.touchSlop
        var zoomed = false
        var panned = false
        var pastPanSlop = false
        var levelDrag = false
        var seekDrag = false
        var totalX = 0f
        var totalY = 0f
        var startLevel = 0f
        var seekMode = ProgressSeekMode.Medium
        var seekAnchorMs = 0L
        var seekDurationMs = 0L
        var seekPreviewMs = 0L
        while (true) {
            val event = awaitPointerEvent()
            if (!event.changes.fastAny { it.pressed }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val centroid = event.calculateCentroid()
            val pointerCount = event.changes.count { it.pressed }
            if (pointerCount >= 2) {
                if (levelDrag) {
                    levelDrag = false
                    onLevelDragEnd()
                }
                if (seekDrag) {
                    seekDrag = false
                    onSeekDragCancel()
                }
                zoomed = true
                pastPanSlop = true
                onGesture(centroid, panChange, zoomChange)
                event.changes.fastForEach {
                    if (it.positionChanged()) it.consume()
                }
            } else if (canPan() && !levelDrag && !seekDrag) {
                if (!pastPanSlop) {
                    pastPanSlop = abs(panChange.x) > touchSlop || abs(panChange.y) > touchSlop
                }
                if (pastPanSlop) {
                    panned = true
                    onGesture(centroid, panChange, 1f)
                    event.changes.fastForEach {
                        if (it.positionChanged()) it.consume()
                    }
                }
            } else {
                totalX += panChange.x
                totalY += panChange.y
                if (!levelDrag && !seekDrag && verticalDragExceededSlop(totalX, totalY, touchSlop)) {
                    levelDrag = true
                    startLevel = onLevelDragStart(side)
                }
                if (!levelDrag && !seekDrag && horizontalDragExceededSlop(totalX, totalY, touchSlop)) {
                    seekDrag = true
                    seekMode = progressSeekModeForStartY(down.position.y, height)
                    val session = onSeekDragStart(seekMode)
                    seekAnchorMs = session.anchorMs
                    seekDurationMs = session.durationMs
                    seekPreviewMs = seekAnchorMs
                }
                if (levelDrag) {
                    onLevelDrag(side, startLevel, totalY, height)
                    event.changes.fastForEach {
                        if (it.positionChanged()) it.consume()
                    }
                } else if (seekDrag) {
                    seekPreviewMs = seekTargetMs(seekAnchorMs, totalX, width, seekDurationMs, seekMode)
                    onSeekDrag(seekPreviewMs)
                    event.changes.fastForEach {
                        if (it.positionChanged()) it.consume()
                    }
                }
            }
        }
        if (levelDrag) onLevelDragEnd()
        if (seekDrag) onSeekDragEnd(seekPreviewMs)
        if (!zoomed && !panned && !levelDrag && !seekDrag) onTap()
    }
}
