package com.jianqiaofan.subtitleplayer.domain.screenshot

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.tags.SubtitleListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotTest {
    private val sample = """
        {
          "version": 1,
          "screenshots": [
            {
              "id": "a1b2c3d4e5f6",
              "title": "画面标题",
              "time": 12.5,
              "frame": 375,
              "image": "a1b2c3d4e5f6.png",
              "created_at": 1758000000000,
              "updated_at": 1758000065000,
              "notes": [
                {
                  "id": "b1b2c3d4e5f6",
                  "text": "笔记正文",
                  "created_at": 1758000000000,
                  "updated_at": 1758000065000,
                  "box": {
                    "x": 0.18,
                    "y": 0.56,
                    "width": 0.64,
                    "height": 0.3,
                    "background": "#FFFFFF",
                    "opacity": 0.85,
                    "font": 0.06,
                    "color": "#1A1A1A",
                    "align": "center"
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesDesktopDocumentAndOmitsMissingBox() {
        val document = parseScreenshotDocument(sample)
        val shot = document!!.screenshots.single()
        assertEquals("a1b2c3d4e5f6", shot.id)
        assertEquals(12.5, shot.time, 0.0)
        assertEquals(375L, shot.frame)
        assertEquals("a1b2c3d4e5f6.png", shot.image)
        val box = shot.notes.single().box!!
        assertEquals(0.18, box.x, 0.0001)
        assertEquals(0.06, box.font, 0.0001)
        assertEquals("center", box.align)
        val noBox = sample.replace(Regex(""",\s*"box": \{[\s\S]*?\}"""), "")
        val plain = parseScreenshotDocument(noBox)!!.screenshots.single().notes.single()
        assertNull(plain.box)
        assertFalse(encodeScreenshotDocument(parseScreenshotDocument(noBox)!!).contains("\"box\""))
    }

    @Test
    fun oldPixelFontIsScaledFrom480AndClamped() {
        assertEquals(0.1, normalizeFont(48.0), 0.0001)
        assertEquals(FONT_MAX, normalizeFont(200.0), 0.0001)
        assertEquals(FONT_MIN, normalizeFont(5.0), 0.0001)
        assertEquals(0.06, normalizeFont(0.06), 0.0001)
        assertEquals(FONT_MAX, normalizeFont(1.0), 0.0001)
    }

    @Test
    fun opacityAlignAndColorsStayInRange() {
        assertEquals(OPACITY_MIN, normalizeOpacity(0.01), 0.0)
        assertEquals(1.0, normalizeOpacity(2.0), 0.0)
        assertEquals("left", normalizeAlign("LEFT"))
        assertEquals("center", normalizeAlign("justify"))
        assertEquals("#1A1A1A", normalizeColor("#1a1a1a", NOTE_COLOR_WHITE))
        assertEquals(NOTE_COLOR_WHITE, normalizeColor("red", NOTE_COLOR_WHITE))
    }

    @Test
    fun baselineIsEmptyUnlessTheManifestOrAWipeSaysSo() {
        val seen = listOf("a1b2c3d4e5f6")
        assertEquals(emptyList<String>(), baselineIdsForSync(manifestPresent = false, wiped = false, seenIds = seen))
        assertEquals(seen, baselineIdsForSync(manifestPresent = true, wiped = false, seenIds = seen))
        assertEquals(seen, baselineIdsForSync(manifestPresent = false, wiped = true, seenIds = seen))
    }

    @Test
    fun mergeKeepsNewerNotesAndDoesNotResurrectALocalDelete() {
        val older = shot("a1b2c3d4e5f6", updated = 10, title = "旧")
        val newer = shot("a1b2c3d4e5f6", updated = 20, title = "新")
        val createdLater = shot("bbbbbbbbbbbb", updated = 30, title = "刚截的")
        val remote = listOf(remoteOf(newer, hasImage = true))
        val merged = mergeScreenshots(setOf(older.id), listOf(newer, createdLater), remote)
        assertEquals("新", merged.first { it.id == older.id }.title)
        assertEquals("a1b2c3d4e5f6.png", merged.first { it.id == older.id }.image)
        assertTrue(merged.any { it.id == createdLater.id })

        val localWins = mergeScreenshots(setOf(older.id), listOf(newer), listOf(remoteOf(older, hasImage = false)))
        assertEquals("新", localWins.single().title)

        val deleted = mergeScreenshots(setOf(older.id), emptyList(), listOf(remoteOf(older, hasImage = true)))
        assertTrue(deleted.isEmpty())

        val downloaded = mergeScreenshots(emptySet(), emptyList(), listOf(remoteOf(older, hasImage = true)))
        assertEquals("", downloaded.single().image)
        assertEquals("旧", downloaded.single().title)
    }

    @Test
    fun imagePlanUsesTheOriginalAndDoesNotReupload() {
        assertEquals(ShotImageWork.None, planShotImage(hasPng = true, frame = 10, hasImage = true))
        assertEquals(ShotImageWork.Upload, planShotImage(hasPng = true, frame = 10, hasImage = false))
        assertEquals(ShotImageWork.Extract, planShotImage(hasPng = false, frame = 10, hasImage = true))
        assertEquals(ShotImageWork.Download, planShotImage(hasPng = false, frame = null, hasImage = true))
        assertEquals(ShotImageWork.None, planShotImage(hasPng = false, frame = null, hasImage = false))
        assertEquals(ShotImageWork.Download, planShotImage(hasPng = false, frame = null, hasImage = true))
    }

    @Test
    fun sameMillisecondKeepsEverySubtitleBeforeScreenshots() {
        val cues = listOf(
            SubtitleCue(1, 1.0, 2.0, "前"),
            SubtitleCue(2, 12.5, 13.0, "同时"),
            SubtitleCue(3, 12.5, 14.0, "同时二"),
            SubtitleCue(4, 20.0, 21.0, "后"),
        )
        val rows = cues.indices.map { SubtitleListRow.Cue(it) }
        val shots = listOf(
            shot("aaaaaaaaaaaa", updated = 1, title = "早", time = 0.5),
            shot("bbbbbbbbbbbb", updated = 1, title = "齐", time = 12.5),
            shot("cccccccccccc", updated = 1, title = "晚", time = 30.0),
        )
        val merged = mergeScreenshotRows(rows, cues, shots)
        assertEquals(
            listOf("aaaaaaaaaaaa", "0", "1", "2", "bbbbbbbbbbbb", "3", "cccccccccccc"),
            merged.map { row ->
                when (row) {
                    is PlaybackRow.Shot -> row.id
                    is PlaybackRow.Cue -> row.cueIndex.toString()
                    is PlaybackRow.Unmatched -> row.entryId
                }
            },
        )
    }

    @Test
    fun plainExportNameAndWebpEdge() {
        assertEquals("zm-截图.jpg", plainScreenshotFileName("电脑", ""))
        assertEquals("zm-画面标题.jpg", plainScreenshotFileName("电脑", "画面标题"))
        assertEquals("zm-画面 标题.jpg", plainScreenshotFileName("电脑", "画面/标题"))
        assertEquals("zm-白板.jpg", screenshotExportFileName("白板"))
        assertEquals(1280 to 720, fittedEdge(1920, 1080))
        assertEquals(640 to 480, fittedEdge(640, 480))
        assertEquals(13L, frameIndexAt(0.5, 25f))
        assertNull(frameIndexAt(1.0, 0f))
        assertNull(frameIndexAt(1.0, null))
    }

    @Test
    fun putBodyCarriesBaselineOnlyWhenAskedAndRejectsBadNotes() {
        val shot = shot("a1b2c3d4e5f6", updated = 2, title = "画面")
        val body = encodeScreenshotPut("ab".repeat(32), "电脑", listOf(shot), emptyList())
        assertTrue(body.contains("\"baseline_ids\":[]"))
        assertFalse(body.contains("\"image\""))
        assertEquals("截图编号无效", screenshotSyncRejection(listOf(shot.copy(id = "NOPE"))))
        assertEquals("截图笔记过长", screenshotSyncRejection(listOf(shot.copy(notes = listOf(note("b1b2c3d4e5f6", "字".repeat(MAX_NOTE_JSON_BYTES)))))))
        assertNull(screenshotSyncRejection(listOf(shot)))
        assertNull(parseScreenshotDocument("{"))
        assertNull(parseScreenshotDocument("{}"))
    }

    @Test
    fun noteBoxStaysInsideThePicture() {
        val box = NoteBox(0.2, 0.2, 0.4, 0.3)
        val moved = moveNoteBox(box, 1.0, -1.0)
        assertEquals(0.6, moved.x, 0.0001)
        assertEquals(0.0, moved.y, 0.0001)
        val grown = resizeNoteBox(box, 1.0, 1.0)
        assertEquals(0.8, grown.width, 0.0001)
        assertEquals(0.8, grown.height, 0.0001)
        val fromCorner = resizeNoteBox(box, -0.1, -0.05, fromLeft = true, fromTop = true)
        assertEquals(0.1, fromCorner.x, 0.0001)
        assertEquals(0.15, fromCorner.y, 0.0001)
        assertEquals(0.5, fromCorner.width, 0.0001)
        assertEquals(0.35, fromCorner.height, 0.0001)
        val rect = fittedImageRect(200f, 100f, 100f, 100f)
        assertEquals(50f, rect.left, 0.01f)
        assertEquals(100f, rect.width, 0.01f)
        assertEquals(NOTE_MIN_WIDTH, moveNoteBox(NoteBox(0.0, 0.0, 0.01, 0.01), 0.0, 0.0).width, 0.0001)
        assertEquals(NoteResizeEdge.BottomRight, hitNoteEdge(40f, 40f, 50f, 50f, hit = 14f))
        assertEquals(NoteResizeEdge.Move, hitNoteEdge(25f, 25f, 50f, 50f, hit = 14f))
        val panel = placeStylePanel(
            anchorLeft = 40f, anchorTop = 40f, anchorWidth = 80f, anchorHeight = 40f,
            panelWidth = 208f, panelHeight = 220f,
            boundsLeft = 0f, boundsTop = 0f, boundsWidth = 400f, boundsHeight = 300f,
        )
        assertTrue(panel.top >= 80f)
        assertEquals("这条笔记", notePanelTitle(""))
        assertEquals("一二三四五六七八九十", notePanelTitle("一二三四五六七八九十"))
        assertTrue(notePanelTitle("字".repeat(30)).endsWith("…"))
        assertTrue(isLightBackground("#FFFFFF"))
        assertFalse(isLightBackground("#1A1A1A"))
        assertEquals(0.25, defaultNoteBox().x, 0.0001)
        assertEquals(0.5, defaultNoteBox().width, 0.0001)
        assertTrue(defaultNoteBox(0, 2).x < defaultNoteBox(1, 2).x)
    }

    @Test
    fun titleAndNearbyCuesFollowThePlayhead() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 1.0, "第一句"),
            SubtitleCue(2, 5.0, 6.0, "当前句"),
            SubtitleCue(3, 10.0, 11.0, "后面"),
            SubtitleCue(4, 12.0, 13.0, "   "),
        )
        assertEquals("考试介绍", suggestedScreenshotTitle("考试介绍", emptyList()))
        assertEquals("考试介绍-真题", suggestedScreenshotTitle("考试介绍", listOf("真题")))
        assertEquals("考试介绍-待复习-真题", suggestedScreenshotTitle("考试介绍", listOf("真题", "待复习")))
        assertEquals("考试介绍-真题", appendScreenshotTitleTags("考试介绍", listOf("真题")))
        assertEquals("考试介绍-待复习-真题", appendScreenshotTitleTags("考试介绍", listOf("真题", "待复习")))
        assertEquals("考试介绍", appendScreenshotTitleTags("考试介绍", emptyList()))
        assertTrue(titleHasKnownTag("考试介绍-真题", listOf("真题")))
        assertTrue(titleHasKnownTag("考试介绍-还没想好", listOf("还没想好")))
        assertFalse(titleHasKnownTag("考试介绍", listOf("真题")))
        assertFalse(titleHasKnownTag("考试介绍-随便写的", listOf("真题")))

        val near = nearbyCuesAroundTime(
            (1..450).map { SubtitleCue(it, it.toDouble(), it + 0.5, "第${it}句") },
            timeSec = 250.5,
        )
        assertEquals(401, near.cues.size)
        assertEquals(200, near.centerInWindow)
        assertEquals(50, near.cues.first().index)
        assertEquals(450, near.cues.last().index)
        assertEquals(3, nearbyCuesAroundTime(cues, 5.5).cues.size)
        assertEquals(1, nearbyCuesAroundTime(cues, 5.5).centerInWindow)
    }

    @Test
    fun screenshotListUsesYouJieTuLabel() {
        val shot = shot("a1b2c3d4e5f6", updated = 1, title = "画面", time = 12.5)
        assertTrue(screenshotListLabel(shot).startsWith("有截图"))
        assertTrue(screenshotListLabel(shot.copy(title = "")).contains("（无标题）"))
        assertFalse(screenshotListLabel(shot).startsWith("截图  "))
    }

    private fun shot(id: String, updated: Long, title: String, time: Double = 12.5) = ScreenshotShot(
        id = id,
        title = title,
        time = time,
        frame = 375,
        image = "$id.png",
        createdAt = 1,
        updatedAt = updated,
        notes = listOf(note("b1b2c3d4e5f6", "笔记")),
    )

    private fun note(id: String, text: String) = ScreenshotNote(id, text, 1, 2, null)

    private fun remoteOf(shot: ScreenshotShot, hasImage: Boolean) = RemoteScreenshot(
        id = shot.id,
        title = shot.title,
        time = shot.time,
        frame = shot.frame,
        createdAt = shot.createdAt,
        updatedAt = shot.updatedAt,
        notes = shot.notes,
        hasImage = hasImage,
        imageHash = if (hasImage) "aa" else "",
    )

    @Test
    fun noteTagLabel_usesTwelveCharsWithoutNewlines() {
        assertEquals("一二三四五六七八九十十一", noteTagLabel("一二三四五六七八九十十一十二十三"))
        assertEquals("第一行第二行", noteTagLabel("第一行\n第二行"))
        assertEquals("Hello", noteTagLabel("Hello"))
        assertEquals("空笔记", noteTagLabel(""))
        assertEquals("next", noteTagLabel("  \n next"))
    }

    @Test
    fun joinCueTextsForNote_chineseUsesFullWidthCommaAndPeriod() {
        assertEquals("你好，世界。", joinCueTextsForNote(listOf("你好", "世界")))
    }

    @Test
    fun joinCueTextsForNote_englishUsesHalfWidthCommaAndPeriod() {
        assertEquals("Hello,world.", joinCueTextsForNote(listOf("Hello", "world")))
        assertEquals("Hi.,There.", joinCueTextsForNote(listOf("Hi。", "There")))
    }

    @Test
    fun joinCueTextsForNote_flattensMultilineCue() {
        assertEquals("第一行 第二行。", joinCueTextsForNote(listOf("第一行\n第二行")))
    }

    @Test
    fun appendJoinedCueTexts_startsNewLine() {
        assertEquals("已有内容。\n新内容。", appendJoinedCueTexts("已有内容。", "新内容。"))
        assertEquals("已有\n新内容。", appendJoinedCueTexts("已有", "新内容。"))
    }
}
