package com.jianqiaofan.subtitleplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleListScrollTest {
    @Test
    fun itemAlreadyCenteredNeedsNoDelta() {
        val delta = subtitleCenterScrollDelta(
            itemOffset = 40,
            itemSize = 20,
            viewportStart = 0,
            viewportEnd = 100,
        )
        assertEquals(0, delta)
    }

    @Test
    fun itemBelowCenterScrollsUp() {
        val delta = subtitleCenterScrollDelta(
            itemOffset = 80,
            itemSize = 20,
            viewportStart = 0,
            viewportEnd = 100,
        )
        assertEquals(40, delta)
    }
}
