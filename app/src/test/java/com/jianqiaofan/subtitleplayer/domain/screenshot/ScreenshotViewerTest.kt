package com.jianqiaofan.subtitleplayer.domain.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotViewerTest {
    @Test
    fun nearestAndSortedByTime() {
        val shots = listOf(
            shot("aaaaaaaaaaaa", time = 12.0),
            shot("bbbbbbbbbbbb", time = 3.0),
            shot("cccccccccccc", time = 7.5),
        )
        val ordered = sortedScreenshots(shots)
        assertEquals(listOf("bbbbbbbbbbbb", "cccccccccccc", "aaaaaaaaaaaa"), ordered.map { it.id })
        assertEquals(0, nearestScreenshotIndex(shots, 2.0))
        assertEquals(1, nearestScreenshotIndex(shots, 7.0))
        assertEquals(2, nearestScreenshotIndex(shots, 20.0))
        assertEquals(-1, nearestScreenshotIndex(emptyList(), 1.0))
    }

    @Test
    fun noteLabelsAndReuseStyleKeepPosition() {
        val early = note("111111111111", "甲", created = 1, box = NoteBox(0.1, 0.2, 0.3, 0.25, font = 0.08, color = NOTE_COLOR_RED))
        val late = note("222222222222", "乙", created = 2, box = NoteBox(0.5, 0.55, 0.2, 0.2))
        val notes = listOf(early, late)
        assertEquals("Note 1", noteNumberLabel(notes, early.id))
        assertEquals("Note 2", noteNumberLabel(notes, late.id))
        assertEquals("Note", noteNumberLabel(listOf(early), early.id))
        assertEquals("Note", stylePanelNoteTitle(listOf(early), early.id))
        assertEquals("Note 1", stylePanelNoteTitle(notes, early.id))
        assertEquals("Note 2", stylePanelNoteTitle(notes, late.id))
        val reused = reuseNoteStyle(late, early, nowMs = 9)
        val box = requireNotNull(reused.box)
        assertEquals(0.5, box.x, 1e-6)
        assertEquals(0.55, box.y, 1e-6)
        assertEquals(0.3, box.width, 1e-6)
        assertEquals(0.08, box.font, 1e-6)
        assertEquals(NOTE_COLOR_RED, box.color)
        assertEquals(9L, reused.updatedAt)
        val spring = NOTE_STYLE_PRESETS.first { it.id == "spring" }
        val withTitle = applyNoteStylePreset(early.box!!, spring)
        val reusedTitle = reuseNoteStyle(late, early.copy(box = withTitle), nowMs = 10).box!!
        assertEquals(spring.titleBackground.lowercase(), reusedTitle.titleBackground.lowercase())
        assertTrue(reusedTitle.titleBold)
        assertTrue(reusedTitle.titleItalic)
    }

    @Test
    fun managePagingByPath() {
        val dirs = listOf("a", "a", "b/c")
        val groups = groupManagedIndicesByPath(dirs)
        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1), groups[0].items)
        assertEquals(listOf(2), groups[1].items)
        assertEquals(1, managePageCount(ManagePagingMode.All, groups))
        assertEquals(2, managePageCount(ManagePagingMode.ByPath, groups))
        assertEquals(listOf(0, 1, 2), managePageIndices(ManagePagingMode.All, groups, 3, 0))
        assertEquals(listOf(2), managePageIndices(ManagePagingMode.ByPath, groups, 3, 1))
    }

    @Test
    fun viewerContrastThreshold() {
        assertTrue(viewerControlsOnLight(0.7))
        assertFalse(viewerControlsOnLight(0.4))
        assertEquals(1.0, averageLuminance01(255, 255, 255), 1e-6)
    }

    @Test
    fun screenshotContentSameIgnoresStamps() {
        val base = shot("aaaaaaaaaaaa", time = 1.0).copy(
            title = "t",
            notes = listOf(note("111111111111", "hi", created = 1, box = NoteBox(0.1, 0.2, 0.3, 0.2))),
        )
        val stamped = base.copy(
            updatedAt = 99,
            notes = base.notes.map { it.copy(updatedAt = 88) },
        )
        assertTrue(screenshotContentSame(base, stamped))
        assertFalse(screenshotContentSame(base, base.copy(title = "other")))
        assertFalse(
            screenshotContentSame(
                base,
                base.copy(notes = base.notes.map { it.copy(text = "changed") }),
            ),
        )
    }

    private fun shot(id: String, time: Double) = ScreenshotShot(
        id = id,
        title = "",
        time = time,
        frame = null,
        image = "$id.png",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun note(
        id: String,
        text: String,
        created: Long,
        box: NoteBox?,
    ) = ScreenshotNote(id, text, created, created, box)
}
