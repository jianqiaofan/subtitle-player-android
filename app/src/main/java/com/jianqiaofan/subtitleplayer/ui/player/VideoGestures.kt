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

suspend fun PointerInputScope.detectVideoViewportGestures(
    canPan: () -> Boolean,
    onTap: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val touchSlop = viewConfiguration.touchSlop
        var zoomed = false
        var panned = false
        var pastTouchSlop = false
        while (true) {
            val event = awaitPointerEvent()
            if (!event.changes.fastAny { it.pressed }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val centroid = event.calculateCentroid()
            val pointerCount = event.changes.count { it.pressed }
            if (pointerCount >= 2) {
                zoomed = true
                pastTouchSlop = true
                onGesture(centroid, panChange, zoomChange)
                event.changes.fastForEach {
                    if (it.positionChanged()) it.consume()
                }
            } else if (canPan()) {
                if (!pastTouchSlop) {
                    pastTouchSlop = abs(panChange.x) > touchSlop || abs(panChange.y) > touchSlop
                }
                if (pastTouchSlop) {
                    panned = true
                    onGesture(centroid, panChange, 1f)
                    event.changes.fastForEach {
                        if (it.positionChanged()) it.consume()
                    }
                }
            }
        }
        if (!zoomed && !panned) {
            onTap()
        }
    }
}
