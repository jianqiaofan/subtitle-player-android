package com.jianqiaofan.subtitleplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun followPausesWhilePausedOrWhileTheActionMenuIsOpen() {
        assertTrue(subtitleFollowSuspended(playing = false, menuOpen = false, selecting = false))
        assertTrue(subtitleFollowSuspended(playing = true, menuOpen = true, selecting = false))
        assertTrue(subtitleFollowSuspended(playing = true, menuOpen = false, selecting = true))
        assertFalse(subtitleFollowSuspended(playing = true, menuOpen = false, selecting = false))
    }
}
