package com.jianqiaofan.subtitleplayer.domain.timeline

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.TagOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun defaultStepFollowsDuration() {
        assertEquals(10, defaultTimelineStepSeconds(60.0))
        assertEquals(10, defaultTimelineStepSeconds(30.0))
        assertEquals(30, defaultTimelineStepSeconds(61.0))
        assertEquals(30, defaultTimelineStepSeconds(600.0))
        assertEquals(60, defaultTimelineStepSeconds(601.0))
        assertEquals(listOf(5, 10, 15, 30, 60, 120, 300), TIMELINE_STEP_SECONDS)
    }

    @Test
    fun timelineIsAListOfRangesAndNotASubtitleFileNameOnly() {
        val cues = buildTimeline(25.0, 10)
        assertEquals(3, cues.size)
        assertEquals(0.0, cues[0].start, 0.001)
        assertEquals(10.0, cues[0].end, 0.001)
        assertEquals(20.0, cues[2].start, 0.001)
        assertEquals(25.0, cues[2].end, 0.001)
        assertEquals("电脑_时间线.srt", timelineSubtitleFileName("电脑"))
        assertEquals("电脑_时间线.srt.tags.json", timelineTagFileName("电脑"))
        assertTrue(buildTimeline(0.0, 10).isEmpty())
    }

    @Test
    fun tagsMoveOntoTheCueThatContainsStartAndKeepTheId() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 60.0, "第一句"),
            SubtitleCue(2, 60.0, 120.0, "第二句"),
        )
        val timeline = listOf(
            entry("aaaa11111111", 0.0, listOf("重点")),
            entry("bbbb22222222", 65.0, listOf("难点")),
        )
        val existing = listOf(entry("aaaa11111111", 0.0, listOf("重点")).copy(index = 1, end = 60.0, text = "第一句"))
        val result = rehomeTimelineTags(timeline, cues, existing, "2026-09-30T02:00:00Z")
        assertEquals(setOf("aaaa11111111", "bbbb22222222"), result.movedIds)
        assertEquals(2, result.subtitleEntries.size)
        assertEquals("bbbb22222222", result.subtitleEntries[1].id)
        assertEquals(2, result.subtitleEntries[1].index)
        assertEquals("第二句", result.subtitleEntries[1].text)
        assertTrue(result.timelineEntries.all { it.tags.isEmpty() })
        assertTrue(result.timelineEntries.all { it.tagOps.any { op -> op.name.isNotEmpty() && !op.present } })
    }

    @Test
    fun sameCueUnionsTagsInsteadOfDuplicatingTheRow() {
        val cues = listOf(SubtitleCue(1, 0.0, 60.0, "一句"))
        val timeline = listOf(
            entry("aaaa11111111", 1.0, listOf("重点")),
            entry("bbbb22222222", 20.0, listOf("难点")),
        )
        val result = rehomeTimelineTags(timeline, cues, emptyList(), "2026-09-30T02:00:00Z")
        assertEquals(1, result.subtitleEntries.size)
        assertEquals(listOf("重点", "难点"), result.subtitleEntries[0].tags)
        assertEquals(setOf("aaaa11111111", "bbbb22222222"), result.movedIds)
    }

    private fun entry(id: String, start: Double, tags: List<String>) = TagEntry(
        id = id,
        index = 1,
        start = start,
        end = start + 10,
        text = "段",
        tags = tags,
        note = "",
        tagOps = tags.map { TagOp(it, true, "2026-09-30T01:00:00Z") },
    )
}
