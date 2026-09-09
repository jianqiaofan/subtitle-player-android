package com.jianqiaofan.subtitleplayer.domain

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleFormat
import com.jianqiaofan.subtitleplayer.domain.subtitle.NamedSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.subtitle.autoSelectTrack
import com.jianqiaofan.subtitleplayer.domain.subtitle.cuesToFileContent
import com.jianqiaofan.subtitleplayer.domain.subtitle.findCueIndexAtTime
import com.jianqiaofan.subtitleplayer.domain.subtitle.findSubtitlesForMedia
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatCueListLine
import com.jianqiaofan.subtitleplayer.domain.subtitle.loadSubtitleContent
import com.jianqiaofan.subtitleplayer.domain.subtitle.subtitleDisplayName
import com.jianqiaofan.subtitleplayer.domain.time.TimeFields
import com.jianqiaofan.subtitleplayer.domain.time.TimePart
import com.jianqiaofan.subtitleplayer.domain.time.coerceEndAfterStart
import com.jianqiaofan.subtitleplayer.domain.time.confirmEditTimes
import com.jianqiaofan.subtitleplayer.domain.time.nudgeTime
import com.jianqiaofan.subtitleplayer.domain.time.EditConfirmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleIoTest {
    @Test
    fun parsesHandoverSampleSrt() {
        val raw = javaClass.classLoader!!.getResourceAsStream("subtitles/sample.srt")!!.bufferedReader().readText()
        val cues = loadSubtitleContent(raw, SubtitleFormat.Srt)
        assertEquals(3, cues.size)
        assertEquals(1, cues[0].index)
        assertEquals(0.0, cues[0].start, 1e-6)
        assertEquals(3.0, cues[0].end, 1e-6)
        assertEquals("欢迎使用字幕学习播放器 Demo", cues[0].text)
        assertEquals("This is a sample subtitle for testing.", cues[1].text)
    }

    @Test
    fun skipsBadSrtBlocks() {
        val raw = """
            not-a-number
            00:00:00,000 --> 00:00:01,000
            skip me

            1
            00:00:01,000 --> 00:00:02,000
            keep me
        """.trimIndent()
        val cues = loadSubtitleContent(raw, SubtitleFormat.Srt)
        assertEquals(1, cues.size)
        assertEquals("keep me", cues[0].text)
    }

    @Test
    fun acceptsCommaOrDotAndMissingHours() {
        val raw = """
            1
            00:01,5 --> 00:02.25
            short
        """.trimIndent()
        val cues = loadSubtitleContent(raw, SubtitleFormat.Srt)
        assertEquals(1, cues.size)
        assertEquals(1.5, cues[0].start, 1e-6)
        assertEquals(2.25, cues[0].end, 1e-6)
    }

    @Test
    fun stripsBom() {
        val raw = "\uFEFF1\n00:00:00,000 --> 00:00:01,000\nhello\n"
        val cues = loadSubtitleContent(raw, SubtitleFormat.Srt)
        assertEquals("hello", cues[0].text)
    }

    @Test
    fun roundTripsSrtAndVtt() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 1.5, "a"),
            SubtitleCue(2, 1.5, 3.0, "b\nc"),
        )
        val srt = cuesToFileContent(cues, SubtitleFormat.Srt)
        assertTrue(srt.contains(","))
        val parsedSrt = loadSubtitleContent(srt, SubtitleFormat.Srt)
        assertEquals("b\nc", parsedSrt[1].text)

        val vtt = cuesToFileContent(cues, SubtitleFormat.Vtt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("."))
        val parsedVtt = loadSubtitleContent(vtt, SubtitleFormat.Vtt)
        assertEquals(2, parsedVtt.size)
        assertEquals(1, parsedVtt[0].index)
    }

    @Test
    fun findsCueAtTime() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 3.0, "a"),
            SubtitleCue(2, 3.0, 6.0, "b"),
            SubtitleCue(3, 6.0, 9.0, "c"),
        )
        assertEquals(0, findCueIndexAtTime(cues, 1.0))
        assertEquals(0, findCueIndexAtTime(cues, 3.0))
        assertEquals(1, findCueIndexAtTime(cues, 3.01))
        assertEquals(1, findCueIndexAtTime(cues, 5.0))
        assertEquals(2, findCueIndexAtTime(cues, 20.0))
        assertEquals(-1, findCueIndexAtTime(emptyList(), 1.0))
    }

    @Test
    fun listLineUsesSlashForNewlines() {
        val line = formatCueListLine(SubtitleCue(1, 0.0, 3.0, "foo\nbar"))
        assertEquals("1. [00:00 → 00:03] foo / bar", line)
    }
}

class SubtitleMatchTest {
    @Test
    fun matchesDefaultPrefixAndIgnoresOthers() {
        val files = listOf(
            NamedSubtitleFile("课程名.srt", "u1"),
            NamedSubtitleFile("课程名_中文.srt", "u2"),
            NamedSubtitleFile("课程名_英文.srt", "u3"),
            NamedSubtitleFile("课程名_原文混排(多语言).srt", "u4"),
            NamedSubtitleFile("别的视频_中文.srt", "u5"),
        )
        val tracks = findSubtitlesForMedia("课程名.mp4", files)
        assertEquals(listOf("默认", "中文", "原文混排(多语言)", "英文"), tracks.map { it.displayName })
        assertNull(subtitleDisplayName("课程名", "别的视频_中文.srt"))
    }

    @Test
    fun prefersSyncTrack() {
        val files = listOf(
            NamedSubtitleFile("视频_中文.srt", "a"),
            NamedSubtitleFile("视频_同步.srt", "b"),
            NamedSubtitleFile("视频_英文.srt", "c"),
        )
        val tracks = findSubtitlesForMedia("视频.mp4", files)
        assertEquals("同步", autoSelectTrack(tracks)?.displayName)
    }
}

class TimeFieldsTest {
    @Test
    fun secondNudgeCarriesToMinute() {
        val start = TimeFields(0, 0, 59, 0)
        val next = nudgeTime(start, TimePart.Second, 1)
        assertEquals(TimeFields(0, 1, 0, 0), next)
    }

    @Test
    fun milliPlus100Carries() {
        val start = TimeFields(0, 0, 0, 950)
        val next = nudgeTime(start, TimePart.Milli, 100)
        assertEquals(TimeFields(0, 0, 1, 50), next)
    }

    @Test
    fun hoursDoNotWrap() {
        val start = TimeFields(0, 0, 0, 0)
        assertEquals(start, nudgeTime(start, TimePart.Second, -1))
        val max = TimeFields(999, 59, 59, 999)
        assertEquals(max, nudgeTime(max, TimePart.Second, 1))
    }

    @Test
    fun startNotBeforeEndBumpsEndByOneSecond() {
        val start = TimeFields(0, 1, 0, 0)
        val end = TimeFields(0, 0, 30, 0)
        val coerced = coerceEndAfterStart(start, end)
        assertEquals(TimeFields(0, 1, 1, 0), coerced)
        val confirmed = confirmEditTimes(start, end) as EditConfirmResult.Ok
        assertEquals(61.0, confirmed.end, 1e-6)
    }
}
