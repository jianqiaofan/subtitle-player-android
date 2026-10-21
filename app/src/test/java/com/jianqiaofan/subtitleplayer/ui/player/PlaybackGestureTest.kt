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

    @Test
    fun bilibiliSeekUsesVerticalZonesAndHorizontalDirection() {
        assertEquals(ProgressSeekMode.Slow, progressSeekModeForStartY(10f, 300f))
        assertEquals(ProgressSeekMode.Medium, progressSeekModeForStartY(150f, 300f))
        assertEquals(ProgressSeekMode.Fast, progressSeekModeForStartY(290f, 300f))
        assertEquals(22.5f, secondsPerWidthForSeekMode(ProgressSeekMode.Slow), 0.001f)
        assertEquals(90f, secondsPerWidthForSeekMode(ProgressSeekMode.Medium), 0.001f)
        assertEquals(450f, secondsPerWidthForSeekMode(ProgressSeekMode.Fast), 0.001f)

        assertTrue(horizontalDragExceededSlop(30f, 10f, 16f))
        assertFalse(horizontalDragExceededSlop(10f, 30f, 16f))

        // Full-width right swipe in medium zone ≈ +90s.
        assertEquals(
            90_000L,
            seekTargetMs(
                anchorMs = 0L,
                totalDx = 1000f,
                widthPx = 1000f,
                durationMs = 600_000L,
                mode = ProgressSeekMode.Medium,
            ),
        )
        // Left swipe seeks backward; slow zone is finer.
        assertEquals(
            60_000L - 11_250L,
            seekTargetMs(
                anchorMs = 60_000L,
                totalDx = -500f,
                widthPx = 1000f,
                durationMs = 600_000L,
                mode = ProgressSeekMode.Slow,
            ),
        )
        assertEquals(
            0L,
            seekTargetMs(
                anchorMs = 5_000L,
                totalDx = -1000f,
                widthPx = 1000f,
                durationMs = 60_000L,
                mode = ProgressSeekMode.Fast,
            ),
        )
        assertEquals(
            "01:30 / 10:00  (+30秒)",
            formatSeekHud(90_000L, 600_000L, 60_000L),
        )
        assertEquals(
            "00:50 / 10:00  (-10秒)",
            formatSeekHud(50_000L, 600_000L, 60_000L),
        )
    }
}
