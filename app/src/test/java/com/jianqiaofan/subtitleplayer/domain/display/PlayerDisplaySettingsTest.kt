package com.jianqiaofan.subtitleplayer.domain.display

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDisplaySettingsTest {
    @Test
    fun videoPicturePortraitWhenHeightGreater() {
        assertEquals(true, videoPictureIsPortrait(720, 1280))
        assertEquals(false, videoPictureIsPortrait(1920, 1080))
        assertNull(videoPictureIsPortrait(0, 0))
        assertNull(videoPictureIsPortrait(100, 100))
    }

    @Test
    fun immersiveOnlyWhenPictureMatchesDevice() {
        // 横幅 + 横屏
        assertTrue(isImmersiveListAvailable(1920, 1080, devicePortrait = false))
        // 横幅 + 竖屏 → 无效
        assertFalse(isImmersiveListAvailable(1920, 1080, devicePortrait = true))
        // 竖幅 + 竖屏
        assertTrue(isImmersiveListAvailable(720, 1280, devicePortrait = true))
        // 竖幅 + 横屏 → 无效
        assertFalse(isImmersiveListAvailable(720, 1280, devicePortrait = false))
    }

    @Test
    fun unknownSizeTreatedAsLandscapeContent() {
        assertTrue(isImmersiveListAvailable(0, 0, devicePortrait = false))
        assertFalse(isImmersiveListAvailable(0, 0, devicePortrait = true))
    }

    @Test
    fun unavailableReasonWhenMismatch() {
        val reason = immersiveUnavailableReason(1920, 1080, devicePortrait = true)
        assertEquals("当前画面方向与屏幕方向不一致，无法使用沉浸列表", reason)
        assertNull(immersiveUnavailableReason(1920, 1080, devicePortrait = false))
    }

    @Test
    fun joinSelectedCueTextsKeepsBodyOnly() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 1.0, "第一句"),
            SubtitleCue(2, 1.0, 2.0, "第二\n行"),
            SubtitleCue(3, 2.0, 3.0, "第三句"),
        )
        assertEquals("第一句\n第三句", joinSelectedCueTexts(cues, listOf(0, 2)))
        assertEquals("第二\n行", joinSelectedCueTexts(cues, listOf(1)))
    }

    @Test
    fun clampBounds() {
        val clamped = PlayerDisplaySettings(
            onscreenFontSize = 3,
            onscreenBgOpacity = 2f,
            onscreenWidthPercent = 10,
            immersiveListSizePercent = 90,
            onscreenColor = "ff00aa",
        ).clamp()
        assertEquals(12, clamped.onscreenFontSize)
        assertEquals(1f, clamped.onscreenBgOpacity)
        assertEquals(30, clamped.onscreenWidthPercent)
        assertEquals(70, clamped.immersiveListSizePercent)
        assertEquals("#FF00AA", clamped.onscreenColor)
    }
}
