package com.jianqiaofan.subtitleplayer.domain.playback

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProgressTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun percentUsesSavedDuration() {
        val record = PlaybackRecord(positionMs = 30_000L, durationMs = 120_000L, leftAt = 1L)
        assertEquals(25, resolvePlayedPercent(record, mediaDurationMs = 999L))
    }

    @Test
    fun percentFallsBackToMediaDuration() {
        val record = PlaybackRecord(positionMs = 50_000L, durationMs = 0L, leftAt = 1L)
        assertEquals(50, resolvePlayedPercent(record, mediaDurationMs = 100_000L))
    }

    @Test
    fun oldPositionWithoutTimeStillShowsPercent() {
        val record = PlaybackRecord(positionMs = 10_000L)
        assertEquals("已看 10%", watchProgressLabel(record, mediaDurationMs = 100_000L, now = 0L))
    }

    @Test
    fun labelShowsLocalLeaveTimeAndPercent() {
        val left = LocalDateTime.of(2026, 9, 26, 21, 5).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 9, 26, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val record = PlaybackRecord(positionMs = 90_000L, durationMs = 180_000L, leftAt = left)
        assertEquals("今天 21:05 · 已看 50%", watchProgressLabel(record, null, now, zone))
    }

    @Test
    fun yesterdayUsesYesterdayLabel() {
        val left = LocalDateTime.of(2026, 9, 25, 8, 1).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 9, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("昨天 08:01", formatLastWatched(left, now, zone))
    }

    @Test
    fun neverWatchedStaysBlank() {
        assertNull(watchProgressLabel(null, 1000L))
    }

    @Test
    fun decodeKeepsOlderPositionOnlyRows() {
        val decoded = decodePlayback("content://video\t15000")
        assertEquals(15_000L, decoded["content://video"]?.positionMs)
        assertEquals(0L, decoded["content://video"]?.leftAt)
    }
}
