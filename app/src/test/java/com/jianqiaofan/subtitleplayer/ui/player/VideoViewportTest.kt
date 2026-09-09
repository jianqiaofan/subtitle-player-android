package com.jianqiaofan.subtitleplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoViewportTest {
    @Test
    fun pinchAtCenterKeepsOffsetZero() {
        val next = applyVideoViewportGesture(
            current = VideoViewport(),
            zoom = 2f,
            panX = 0f,
            panY = 0f,
            centroidX = 100f,
            centroidY = 50f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(2f, next.scale, 1e-4f)
        assertEquals(0f, next.offsetX, 1e-3f)
        assertEquals(0f, next.offsetY, 1e-3f)
    }

    @Test
    fun pinchTowardEdgeShiftsOffsetSoPointStays() {
        val next = applyVideoViewportGesture(
            current = VideoViewport(),
            zoom = 2f,
            panX = 0f,
            panY = 0f,
            centroidX = 200f,
            centroidY = 50f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(2f, next.scale, 1e-4f)
        assertEquals(-100f, next.offsetX, 1e-2f)
        assertEquals(0f, next.offsetY, 1e-2f)
    }

    @Test
    fun panIsKeptUntilClamped() {
        val zoomed = VideoViewport(scale = 2f, offsetX = 0f, offsetY = 0f)
        val next = applyVideoViewportGesture(
            current = zoomed,
            zoom = 1f,
            panX = 20f,
            panY = -10f,
            centroidX = 100f,
            centroidY = 50f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(2f, next.scale, 1e-4f)
        assertEquals(20f, next.offsetX, 1e-3f)
        assertEquals(-10f, next.offsetY, 1e-3f)
    }

    @Test
    fun panDoesNotLeaveEmptyEdges() {
        val next = applyVideoViewportGesture(
            current = VideoViewport(scale = 2f),
            zoom = 1f,
            panX = 10_000f,
            panY = 10_000f,
            centroidX = 100f,
            centroidY = 50f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(100f, next.offsetX, 1e-3f)
        assertEquals(50f, next.offsetY, 1e-3f)
    }

    @Test
    fun pinchBackToOneResetsOffset() {
        val next = applyVideoViewportGesture(
            current = VideoViewport(scale = 3f, offsetX = 40f, offsetY = -20f),
            zoom = 1f / 3f,
            panX = 0f,
            panY = 0f,
            centroidX = 10f,
            centroidY = 10f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(1f, next.scale, 1e-4f)
        assertEquals(0f, next.offsetX, 1e-3f)
        assertEquals(0f, next.offsetY, 1e-3f)
    }

    @Test
    fun scaleIsCapped() {
        val next = applyVideoViewportGesture(
            current = VideoViewport(scale = 5f),
            zoom = 4f,
            panX = 0f,
            panY = 0f,
            centroidX = 100f,
            centroidY = 50f,
            viewportWidth = 200f,
            viewportHeight = 100f,
        )
        assertEquals(VIDEO_MAX_SCALE, next.scale, 1e-4f)
    }
}
