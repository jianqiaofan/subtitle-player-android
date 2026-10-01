package com.jianqiaofan.subtitleplayer.domain.playbacklog

import com.jianqiaofan.subtitleplayer.domain.cloud.LeaveAction
import com.jianqiaofan.subtitleplayer.domain.cloud.LeavePolicy
import com.jianqiaofan.subtitleplayer.domain.cloud.PendingChanges
import com.jianqiaofan.subtitleplayer.domain.cloud.itemsToUpload
import com.jianqiaofan.subtitleplayer.domain.cloud.leaveAction
import com.jianqiaofan.subtitleplayer.domain.cloud.parseStudySyncMemory
import java.time.ZoneId
import java.time.zone.ZoneRulesException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLogTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun shortSessionsAreDroppedAndMergeDoesNotReplaceAnExistingId() {
        val open = beginSession(PlaybackLog(), "a".repeat(32), 1_758_000_000_000L)
        val dropped = closeSession(open, "a".repeat(32), 1_758_000_000_400L)
        assertTrue(dropped.sessions.isEmpty())
        val kept = closeSession(open, "a".repeat(32), 1_758_000_002_000L)
        assertEquals(1_758_000_002_000L, kept.sessions.single().endedAt)
        val remote = PlaybackSession("a".repeat(32), 1L, 9L)
        val other = PlaybackSession("b".repeat(32), 1_758_000_003_000L, 1_758_000_004_000L)
        val merged = mergePlaybackSessions(kept.sessions, listOf(remote, other))
        assertEquals(1_758_000_002_000L, merged.first { it.id == "a".repeat(32) }.endedAt)
        assertTrue(merged.any { it.id == "b".repeat(32) })
    }

    @Test
    fun uploadSkipsTheOpenSessionAndAnotherAccount() {
        val mine = PlaybackSession("a".repeat(32), PLAYBACK_EARLIEST_MS + 10_000, PLAYBACK_EARLIEST_MS + 12_000)
        val theirs = PlaybackSession("b".repeat(32), PLAYBACK_EARLIEST_MS + 10_000, PLAYBACK_EARLIEST_MS + 12_000)
        val open = PlaybackSession("c".repeat(32), PLAYBACK_EARLIEST_MS + 20_000, null)
        val owners = mapOf(mine.id to "zhang", theirs.id to "li")
        val upload = sessionsToUpload(listOf(mine, theirs, open), owners, "zhang", openSessionId = open.id)
        assertEquals(listOf(mine.id), upload.map { it.id })
        assertEquals(listOf(mine.id), sessionsForAccount(listOf(mine, theirs), owners, "zhang").map { it.id })
        assertFalse(uploadableSession(mine.copy(endedAt = mine.startedAt)))
        assertFalse(uploadableSession(mine.copy(endedAt = mine.startedAt + PLAYBACK_MAX_MS + 1)))
    }

    @Test
    fun durationAndClockUseTheDeviceZoneThenShanghai() {
        assertEquals("45秒", formatStudyDuration(45_000))
        assertEquals("2分5秒", formatStudyDuration(125_000))
        assertEquals("2小时3分4秒", formatStudyDuration((2 * 3600 + 3 * 60 + 4) * 1000L))
        val line = formatSessionLine(
            PlaybackSession("a".repeat(32), 1_758_000_000_000L, 1_758_000_911_000L),
            zone,
        )
        assertTrue(line!!.contains("→"))
        assertTrue(line.contains("15分11秒"))
        assertEquals(zone, zoneOrShanghai(zone))
        assertEquals(ZoneId.of("Asia/Shanghai"), zoneOrShanghai(null))
        val roundTrip = parsePlaybackLog(encodePlaybackLog(PlaybackLog(sessions = listOf(
            PlaybackSession("a".repeat(32), 1_758_000_000_000L, null),
        ))))
        assertNull(roundTrip.sessions.single().endedAt)
        assertEquals(32, newPlaybackSessionId().length)
    }

    @Test
    fun brokenZoneFallsBackToShanghai() {
        val broken = try {
            ZoneId.of("Not/AZone")
            null
        } catch (_: ZoneRulesException) {
            null
        }
        assertEquals(ZoneId.of("Asia/Shanghai"), zoneOrShanghai(broken))
    }

    @Test
    fun leaveSyncAsksOnlyWhenLoggedInOnlineAndNothingIsRemembered() {
        val dirty = PendingChanges(subtitles = true, tags = false)
        assertEquals(LeaveAction.Skip, leaveAction(false, true, dirty, null))
        assertEquals(LeaveAction.Skip, leaveAction(true, false, dirty, null))
        assertEquals(LeaveAction.Ask, leaveAction(true, true, dirty, null))
        val policy = LeavePolicy(syncSubtitles = true, syncTags = false)
        assertEquals(LeaveAction.Auto, leaveAction(true, true, dirty, policy))
        assertEquals(PendingChanges(subtitles = true, tags = false), itemsToUpload(policy, PendingChanges(true, true)))
        val memory = parseStudySyncMemory(
            """{"policies":{"h":{"syncSubtitles":true,"syncTags":false}},"pending":{"h":{"subtitles":true,"tags":false}},"owners":{"abc":"zhang"}}""",
        )
        assertTrue(memory.policies.getValue("h").syncSubtitles)
        assertFalse(memory.policies.getValue("h").syncTags)
        assertEquals("zhang", memory.sessionOwners.getValue("abc"))
    }
}
