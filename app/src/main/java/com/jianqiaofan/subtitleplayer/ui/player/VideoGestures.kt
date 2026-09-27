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
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ScreenHalf { Left, Right }

const val PLAYBACK_BRIGHTNESS_FLOOR = 0.01f

fun screenHalf(x: Float, width: Float): ScreenHalf =
    if (width > 0f && x >= width / 2f) ScreenHalf.Right else ScreenHalf.Left

/** Finger moving up (negative Y) raises the level. A full-height drag covers the whole range. */
fun levelAfterVerticalDrag(startLevel: Float, dragDistanceY: Float, span: Float): Float {
    if (span <= 0f) return startLevel.coerceIn(0f, 1f)
    return (startLevel - dragDistanceY / span).coerceIn(0f, 1f)
}

fun verticalDragExceededSlop(totalX: Float, totalY: Float, touchSlop: Float): Boolean =
    abs(totalY) > touchSlop && abs(totalY) > abs(totalX)

fun volumeIndexForLevel(level: Float, maxVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return (level.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
}

fun playbackBrightness(level: Float): Float = level.coerceIn(PLAYBACK_BRIGHTNESS_FLOOR, 1f)

fun levelPercent(level: Float): Int = (level.coerceIn(0f, 1f) * 100f).roundToInt()

internal fun subtitleFollowSuspended(playing: Boolean, menuOpen: Boolean, selecting: Boolean): Boolean =
    !playing || menuOpen || selecting

suspend fun PointerInputScope.detectVideoViewportGestures(
    canPan: () -> Boolean,
    onTap: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onLevelDragStart: (ScreenHalf) -> Float,
    onLevelDrag: (ScreenHalf, Float, Float, Float) -> Unit,
    onLevelDragEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val side = screenHalf(down.position.x, size.width.toFloat())
        val touchSlop = viewConfiguration.touchSlop
        var zoomed = false
        var panned = false
        var pastPanSlop = false
        var levelDrag = false
        var horizontalLocked = false
        var totalX = 0f
        var totalY = 0f
        var startLevel = 0f
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
                zoomed = true
                pastPanSlop = true
                onGesture(centroid, panChange, zoomChange)
                event.changes.fastForEach {
                    if (it.positionChanged()) it.consume()
                }
            } else if (canPan() && !levelDrag) {
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
                if (!levelDrag && !horizontalLocked && verticalDragExceededSlop(totalX, totalY, touchSlop)) {
                    levelDrag = true
                    startLevel = onLevelDragStart(side)
                }
                if (levelDrag) {
                    onLevelDrag(side, startLevel, totalY, size.height.toFloat())
                    event.changes.fastForEach {
                        if (it.positionChanged()) it.consume()
                    }
                } else if (abs(totalX) > touchSlop && abs(totalX) >= abs(totalY)) {
                    horizontalLocked = true
                }
            }
        }
        if (levelDrag) onLevelDragEnd()
        if (!zoomed && !panned && !levelDrag && !horizontalLocked) onTap()
    }
}
