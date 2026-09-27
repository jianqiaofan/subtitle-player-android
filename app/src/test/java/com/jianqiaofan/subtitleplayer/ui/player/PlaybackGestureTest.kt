package com.jianqiaofan.subtitleplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGestureTest {
    @Test
    fun leftHalfRaisesBrightnessWhenTheFingerMovesUp() {
        assertEquals(ScreenHalf.Left, screenHalf(10f, 200f))
        assertEquals(ScreenHalf.Right, screenHalf(100f, 200f))
        assertEquals(0.7f, levelAfterVerticalDrag(0.4f, -120f, 400f), 0.001f)
        assertEquals(0.1f, levelAfterVerticalDrag(0.4f, 120f, 400f), 0.001f)
        assertEquals(1f, levelAfterVerticalDrag(0.9f, -400f, 400f))
        assertEquals(0f, levelAfterVerticalDrag(0.1f, 400f, 400f))
        assertEquals(PLAYBACK_BRIGHTNESS_FLOOR, playbackBrightness(0f))
    }

    @Test
    fun rightHalfMapsTheDragOntoVolumeSteps() {
        assertEquals(ScreenHalf.Right, screenHalf(180f, 200f))
        assertTrue(verticalDragExceededSlop(4f, 30f, 16f))
        assertFalse(verticalDragExceededSlop(30f, 20f, 16f))
        assertFalse(verticalDragExceededSlop(2f, 8f, 16f))
        assertEquals(8, volumeIndexForLevel(0.5f, 15))
        assertEquals(0, volumeIndexForLevel(0f, 15))
        assertEquals(15, volumeIndexForLevel(1f, 15))
        assertEquals(70, levelPercent(0.7f))
    }
}
