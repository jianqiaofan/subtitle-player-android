package com.jianqiaofan.subtitleplayer.domain.tags

import com.jianqiaofan.subtitleplayer.domain.display.OnScreenHorizontalSpan
import com.jianqiaofan.subtitleplayer.domain.display.onScreenHorizontalSpan
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.recentMediaMenuLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTagsTest {
    private fun cue(index: Int, start: Double, text: String, end: Double = start + 2.0) =
        SubtitleCue(index, start, end, text)

    private fun entry(
        id: String,
        index: Int,
        start: Double,
        text: String,
        tags: List<String> = listOf("重点"),
        note: String = "",
        end: Double = start + 2.0,
    ) = TagEntry(id, index, start, end, text, tags, note)

    @Test
    fun tagFileNameKeepsSubtitleExtension() {
        assertEquals("lesson_中文.srt.tags.json", tagFileNameFor("lesson_中文.srt"))
        assertEquals("lesson_同步.vtt", subtitleFileNameFromTagFile("lesson_同步.vtt.tags.json"))
        assertNull(subtitleFileNameFromTagFile("lesson_中文.srt"))
    }

    @Test
    fun roundTripPreservesNoteNewlinesAndChinese() {
        val doc = TagDocument(
            version = 1,
            subtitleFile = "lesson_中文.srt",
            entries = listOf(
                entry("a1b2c3d4e5f6", 12, 12.5, "打标签时的整句正文", listOf("重点", "难点"), "这里\"虚拟\"\n语气"),
            ),
        )
        val parsed = parseTagDocument(encodeTagDocument(doc), "lesson_中文.srt")
        assertEquals(doc, parsed)
    }

    @Test
    fun mismatchedSubtitleFileInvalidatesWholeDocument() {
        val raw = encodeTagDocument(
            TagDocument(1, "other.srt", listOf(entry("abc123abc123", 1, 0.0, "你好你好你好"))),
        )
        assertNull(parseTagDocument(raw, "lesson_中文.srt"))
    }

    @Test
    fun emptyTagsAndNoteAreDropped() {
        val raw = """
            {
              "version": 1,
              "subtitle_file": "a.srt",
              "entries": [
                {"id":"abc123abc123","index":1,"start":0,"end":1,"text":"你好你好你好","tags":[],"note":""},
                {"id":"def456def456","index":2,"start":1,"end":2,"text":"还有一句完整台词","tags":["重点"],"note":""}
              ]
            }
        """.trimIndent()
        val doc = parseTagDocument(raw, "a.srt")
        assertEquals(1, doc!!.entries.size)
        assertEquals("def456def456", doc.entries[0].id)
    }

    @Test
    fun emptyDocumentEncodesToNullAlignment() {
        val alignment = TagAlignment()
        assertNull(alignmentToDocument("a.srt", alignment))
    }

    @Test
    fun primaryColorPriorityAndMasteredDim() {
        assertEquals("难点", primaryTag(listOf("重点", "难点", "已掌握")))
        assertEquals("重点", primaryTag(listOf("跟读", "重点")))
        assertEquals("存疑", primaryTag(listOf("难点", "存疑", "已掌握")))
        assertEquals("单词", primaryTag(listOf("已掌握", "单词")))
        assertEquals("已掌握", primaryTag(listOf("已掌握", "我的")))
        assertEquals("我的", primaryTag(listOf("我的", "另一个")))
        assertEquals(listOf("重点", "新页面", "跟读", "课堂"), orderedTagNames(listOf("跟读", "课堂", "新页面", "重点", "新页面")))
        assertTrue(subtitleBodyDimmed(listOf("已掌握")))
        assertFalse(subtitleBodyDimmed(listOf("已掌握", "重点")))
        assertEquals(0xFF6B3030.toInt(), tagPalette("难点").background.toInt())
        assertEquals(0xFF5A2048.toInt(), tagPalette("存疑").background.toInt())
        assertEquals(0xFFFFB0D0.toInt(), tagPalette("存疑").foreground.toInt())
        assertEquals(0xFF3A3A3A.toInt(), tagPalette("自定义").background.toInt())
    }

    @Test
    fun closeTextRequiresExactOrLongSimilar() {
        assertTrue(textsAreClose("你好", "你好"))
        assertFalse(textsAreClose("你好", "你好啊"))
        assertTrue(textsAreClose("欢迎使用字幕学习播放器", "欢迎使用字幕学习播放器"))
        assertTrue(textsAreClose("欢迎 使用字幕学习播放器", "欢迎使用字幕学习播放器"))
        assertFalse(textsAreClose("欢迎使用字幕学习播放器", "这是完全不同的一句台词内容"))
    }

    @Test
    fun alignByTimeThenIndexThenUniqueText() {
        val cues = listOf(
            cue(1, 0.0, "欢迎使用字幕学习播放器 Demo"),
            cue(2, 3.0, "This is a sample subtitle for testing."),
            cue(3, 6.0, "你可以用任意短视频配合此字幕进行功能测试。"),
        )
        val byTime = alignTags(
            cues,
            listOf(entry("111111111111", 9, 3.001, "This is a sample subtitle for testing.", listOf("重点"))),
        )
        assertEquals(1, byTime.attached.keys.single())
        assertTrue(byTime.unmatched.isEmpty())

        val byIndex = alignTags(
            cues,
            listOf(entry("222222222222", 3, 40.0, "你可以用任意短视频配合此字幕进行功能测试。", listOf("难点"))),
        )
        assertEquals(2, byIndex.attached.keys.single())

        val shifted = listOf(
            cue(1, 0.0, "第一句完全不同的内容啊"),
            cue(2, 10.0, "唯一能对上的这句字幕正文"),
            cue(3, 20.0, "第三句也完全不一样啊"),
        )
        val byText = alignTags(
            shifted,
            listOf(entry("333333333333", 8, 50.0, "唯一能对上的这句字幕正文", listOf("跟读"))),
        )
        assertEquals(1, byText.attached.keys.single())
    }

    @Test
    fun duplicateTextAttachesOnlyWhenNearestIsClearlyCloser() {
        val cues = listOf(
            cue(1, 1.0, "相同的一句字幕正文内容"),
            cue(2, 2.0, "相同的一句字幕正文内容"),
            cue(3, 30.0, "相同的一句字幕正文内容"),
        )
        val ambiguous = alignTags(cues, listOf(entry("444444444444", 9, 1.2, "相同的一句字幕正文内容")))
        assertTrue(ambiguous.attached.isEmpty())
        assertEquals(1, ambiguous.unmatched.size)

        val clear = alignTags(cues, listOf(entry("555555555555", 9, 30.1, "相同的一句字幕正文内容")))
        assertEquals(2, clear.attached.keys.single())
    }

    @Test
    fun unmatchedIsNotMovedOntoNearbyCue() {
        val cues = listOf(cue(1, 0.0, "原来的一句字幕内容"), cue(2, 3.0, "旁边的另一句字幕内容"))
        val alignment = alignTags(
            cues,
            listOf(entry("666666666666", 1, 0.0, "被人改掉的完全不同正文")),
        )
        assertTrue(alignment.attached.isEmpty())
        assertEquals("666666666666", alignment.unmatched.single().id)
    }

    @Test
    fun inAppEditKeepsTheSameRow() {
        val cues = listOf(cue(1, 1.0, "原句字幕内容在这里"))
        val alignment = alignTags(cues, listOf(entry("777777777777", 1, 1.0, "原句字幕内容在这里", listOf("重点"), "备注")))
        val edited = cue(1, 8.0, "改过的正文", end = 9.0)
        val next = applyCueEdit(alignment, 0, edited)
        val entry = next.attached.getValue(0)
        assertEquals(8.0, entry.start, 0.0)
        assertEquals(9.0, entry.end, 0.0)
        assertEquals("改过的正文", entry.text)
        assertEquals(listOf("重点"), entry.tags)
        assertEquals("备注", entry.note)
        assertEquals("777777777777", entry.id)
    }

    @Test
    fun multiEditAppliesTagsAndOptionalNote() {
        val cues = listOf(cue(1, 0.0, "第一句字幕正文"), cue(2, 2.0, "第二句字幕正文"))
        val start = applyTagEdit(
            TagAlignment(),
            cues,
            TagEdit(listOf(0), listOf("重点"), "甲", applyNote = true),
        ) { "id0000000001" }
        val multi = applyTagEdit(
            start,
            cues,
            TagEdit(listOf(0, 1), listOf("难点"), "乙", applyNote = false),
        ) { "id0000000002" }
        assertEquals(listOf("难点"), multi.attached.getValue(0).tags)
        assertEquals("甲", multi.attached.getValue(0).note)
        assertEquals("", multi.attached.getValue(1).note)
        val cleared = applyTagEdit(
            multi,
            cues,
            TagEdit(listOf(1), emptyList(), "", applyNote = true),
        )
        assertFalse(1 in cleared.attached)
    }

    @Test
    fun attachUnmatchedMergesTagsAndKeepsExistingNote() {
        val cues = listOf(cue(1, 0.0, "当前这句字幕正文"))
        val alignment = TagAlignment(
            attached = mapOf(0 to entry("aaaaaaaaaaaa", 1, 0.0, "当前这句字幕正文", listOf("重点"), "已有")),
            unmatched = listOf(entry("bbbbbbbbbbbb", 4, 9.0, "旧正文", listOf("难点"), "外来")),
        )
        val merged = attachUnmatchedToCue(alignment, "bbbbbbbbbbbb", 0, cues[0])
        assertEquals(listOf("重点", "难点"), merged.attached.getValue(0).tags)
        assertEquals("已有", merged.attached.getValue(0).note)
        assertTrue(merged.unmatched.isEmpty())
    }

    @Test
    fun filterIsAnySelectedTagAndReleasesWhenTagsGone() {
        val alignment = TagAlignment(
            attached = mapOf(
                0 to entry("aaaaaaaaaaaa", 1, 0.0, "甲句字幕正文内容", listOf("重点")),
                2 to entry("bbbbbbbbbbbb", 3, 4.0, "丙句字幕正文内容", listOf("难点", "重点")),
            ),
        )
        val rows = buildListRows(3, alignment, TagListFilter(selectedTags = setOf("难点", "跟读")))
        assertEquals(listOf(SubtitleListRow.Cue(2)), rows)
        val all = buildListRows(3, alignment, TagListFilter())
        assertEquals(3, all.size)
        assertEquals(TagListFilter(), releaseFilterIfNoTags(TagAlignment(), TagListFilter(selectedTags = setOf("重点"))))
    }

    @Test
    fun jumpSkipsUntaggedCues() {
        val cues = listOf(
            cue(1, 0.0, "一"),
            cue(2, 2.0, "二"),
            cue(3, 4.0, "三"),
        )
        val alignment = TagAlignment(
            attached = mapOf(
                0 to entry("aaaaaaaaaaaa", 1, 0.0, "一"),
                2 to entry("bbbbbbbbbbbb", 3, 4.0, "三", listOf("难点")),
            ),
        )
        assertEquals(2, adjacentTaggedCue(cues, alignment, TagListFilter(), currentCueIndex = 0, forward = true))
        assertNull(adjacentTaggedCue(cues, alignment, TagListFilter(selectedTags = setOf("难点")), 1, forward = false))
        assertEquals(2, adjacentTaggedCue(cues, alignment, TagListFilter(selectedTags = setOf("难点")), 1, forward = true))
        assertNull(adjacentTaggedCue(cues, alignment, TagListFilter(selectedTags = setOf("难点")), 2, forward = true))
    }

    @Test
    fun mergeByIdAppendsDifferentNotesAndKeepsLocal() {
        val local = listOf(entry("aaaaaaaaaaaa", 1, 1.0, "同一句字幕的正文内容", listOf("重点"), "本地"))
        val incoming = listOf(
            entry("aaaaaaaaaaaa", 1, 1.0, "同一句字幕的正文内容", listOf("难点"), "外来"),
            entry("bbbbbbbbbbbb", 8, 20.0, "对不上的另一句正文", listOf("跟读"), "新的"),
        )
        val merged = mergeTagEntries(local, incoming)
        assertEquals(2, merged.size)
        assertEquals(setOf("重点", "难点"), merged[0].tags.toSet())
        assertEquals("本地\n外来", merged[0].note)
        assertEquals("bbbbbbbbbbbb", merged[1].id)
    }

    @Test
    fun syncSkipsInvalidAndMergesExisting() {
        val incoming = TagDocument(1, "a.srt", listOf(entry("aaaaaaaaaaaa", 1, 0.0, "字幕正文在这里呀")))
        val skip = planTagSync("notes.tags.json", incoming, true, null, false)
        assertEquals(TagSyncKind.Skipped, skip.kind)
        val copy = planTagSync("a.srt.tags.json", incoming, true, null, false)
        assertEquals(TagSyncKind.Copied, copy.kind)
        val same = planTagSync("a.srt.tags.json", incoming, true, null, true)
        assertEquals(TagSyncKind.Skipped, same.kind)
        val merge = planTagSync(
            "a.srt.tags.json",
            incoming,
            true,
            TagDocument(1, "a.srt", listOf(entry("bbbbbbbbbbbb", 2, 3.0, "另一句字幕正文内容", listOf("易错")))),
            false,
        )
        assertEquals(TagSyncKind.Merged, merge.kind)
        assertEquals(2, merge.document!!.entries.size)
    }

    @Test
    fun batchMatchesSiblingVideosInSubfolders() {
        val doc = TagDocument(1, "课程名_中文.srt", listOf(entry("aaaaaaaaaaaa", 1, 0.0, "字幕正文在这里呀")))
        val rows = matchBatchTargets(
            "课程名_中文.srt.tags.json",
            doc,
            mapOf(
                "" to listOf("别的.mp4", "别的.srt"),
                "Unit 1" to listOf("课程名.mp4", "课程名_中文.srt", "课程名_中文.srt.tags.json", "readme.txt"),
                "Unit 2" to listOf("课程名.mkv", "课程名_中文.srt"),
            ),
        )
        assertEquals(listOf("Unit 1", "Unit 2"), rows.map { it.relativeDir })
        assertTrue(rows[0].hasExistingTag)
        assertFalse(rows[1].hasExistingTag)
        assertTrue(videoMatchesSubtitle("课程名.mp4", "课程名_中文.srt"))
        assertFalse(videoMatchesSubtitle("别的.mp4", "课程名_中文.srt"))
    }

    @Test
    fun customTagsStayWithinFortyEightCharacters() {
        assertEquals("跟读笔记", normalizeCustomTagName("  跟读笔记 "))
        assertNull(normalizeCustomTagName("a".repeat(49)))
        assertNull(normalizeCustomTagName("重点"))
        assertNull(normalizeCustomTagName("新页面"))
        assertNull(normalizeCustomTagName("易错"))
        assertNull(normalizeCustomTagName("课堂\n笔记"))
        assertNull(normalizeCustomTagName("课堂\t笔记"))
        val names = collectCustomTagNames(
            listOf(
                TagDocument(1, "a.srt", listOf(entry("aaaaaaaaaaaa", 1, 0.0, "字幕正文在这里呀", listOf("重点", "口语")))),
                TagDocument(1, "b.srt", listOf(entry("bbbbbbbbbbbb", 1, 0.0, "另一份字幕正文呀", listOf("口语", "语法", "课堂笔记")))),
            ),
        )
        assertEquals(listOf("口语", "课堂笔记"), names)
    }

    @Test
    fun onScreenCaptionShiftsBesideImmersiveList() {
        assertEquals(OnScreenHorizontalSpan.Full, onScreenHorizontalSpan(false, true, false, 0.36f))
        assertEquals(OnScreenHorizontalSpan.Full, onScreenHorizontalSpan(true, false, false, 0.36f))
        val right = onScreenHorizontalSpan(true, true, listOnLeft = false, listFraction = 0.36f)
        assertEquals(0f, right.startFraction)
        assertEquals(0.64f, right.endFraction, 0.001f)
        val left = onScreenHorizontalSpan(true, true, listOnLeft = true, listFraction = 0.20f)
        assertEquals(0.20f, left.startFraction, 0.001f)
        assertEquals(1f, left.endFraction)
    }

    @Test
    fun recentNamesFromDifferentFoldersAreDisambiguated() {
        val items = listOf(
            RecentMedia("a", "课程.mp4", "英语"),
            RecentMedia("b", "课程.mp4", "日语"),
            RecentMedia("c", "别的.mp4", "英语"),
        )
        assertEquals("课程.mp4 — 英语", recentMediaMenuLabel(items[0], items))
        assertEquals("别的.mp4", recentMediaMenuLabel(items[2], items))
    }

    @Test
    fun customNamesStayWithTheLongerMediaStem() {
        val names = listOf(
            "lesson.mp4",
            "lesson_2.mp4",
            "lesson.srt",
            "lesson_中文.srt",
            "lesson_2.srt",
            "lesson_2_中文.srt",
            "other.mp4",
            "other.srt",
        )
        assertEquals(listOf("lesson.srt", "lesson_中文.srt"), companionSubtitleNames("lesson.mp4", names))
        assertEquals(listOf("lesson_2.srt", "lesson_2_中文.srt"), companionSubtitleNames("lesson_2.mp4", names))
        assertEquals(listOf("other.srt"), companionSubtitleNames("other.mp4", names))
        assertEquals("lesson_2", subtitleOwnerStem("lesson_2_中文.srt", names))
    }

    @Test
    fun extractCopiesOneFileAndMergesSameNames() {
        val copied = planTagExtract(
            listOf(source("lesson_中文.srt.tags.json", doc("lesson_中文.srt", entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器", listOf("重点"))))),
            emptyMap(),
        )
        assertEquals(1, copied.writes.size)
        assertEquals(TagSyncKind.Copied, copied.writes[0].kind)
        assertEquals("lesson_中文.srt", copied.writes[0].document.subtitleFile)

        val first = doc("lesson_中文.srt", entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器", listOf("重点"), note = "甲"))
        val second = doc(
            "lesson_中文.srt",
            entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器", listOf("新页面"), note = "乙"),
            entry("bbbbbbbbbbbb", 2, 4.0, "另一句完全不同的字幕正文", listOf("口语")),
        )
        val merged = planTagExtract(
            listOf(
                ExtractSourceFile("a/lesson_中文.srt.tags.json", "lesson_中文.srt.tags.json", encodeTagDocument(first)),
                ExtractSourceFile("b/lesson_中文.srt.tags.json", "lesson_中文.srt.tags.json", encodeTagDocument(second)),
            ),
            emptyMap(),
        )
        assertEquals(TagSyncKind.Merged, merged.writes.single().kind)
        val entry = merged.writes.single().document.entries.single { it.id == "aaaaaaaaaaaa" }
        assertEquals(listOf("重点", "新页面"), entry.tags)
        assertEquals("甲\n乙", entry.note)
        assertEquals(2, merged.writes.single().document.entries.size)

        val nearby = planTagExtract(
            listOf(
                source("lesson_中文.srt.tags.json", doc("lesson_中文.srt", entry("id1111111111", 1, 1.0, "第一句完全不同的字幕正文", listOf("重点")))),
                source("lesson_中文.srt.tags.json", doc("lesson_中文.srt", entry("id2222222222", 1, 1.0005, "第二句也是完全不同的正文呀", listOf("难点")))),
            ),
            emptyMap(),
        )
        assertEquals(2, nearby.writes.single().document.entries.size)
    }

    @Test
    fun extractMergesIntoExistingAndSkipsBadFiles() {
        val existing = doc("lesson_中文.srt", entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器", listOf("重点")))
        val incoming = doc("Lesson_中文.srt", entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器", listOf("难点")))
        val plan = planTagExtract(
            listOf(source("Lesson_中文.srt.tags.json", incoming)),
            existingDocuments = mapOf("lesson_中文.srt.tags.json" to existing),
            existingFileNames = mapOf("lesson_中文.srt.tags.json" to "lesson_中文.srt.tags.json"),
        )
        assertEquals(TagSyncKind.Merged, plan.writes.single().kind)
        assertEquals("lesson_中文.srt.tags.json", plan.writes.single().fileName)
        assertEquals(listOf("重点", "难点"), plan.writes.single().document.entries.single().tags)

        val skipped = planTagExtract(
            listOf(
                ExtractSourceFile("bad.tags.json", "lesson_中文.srt.tags.json", null),
                ExtractSourceFile("broken.tags.json", "lesson_中文.srt.tags.json", "{"),
                ExtractSourceFile("mismatch.tags.json", "lesson_中文.srt.tags.json", encodeTagDocument(doc("other.srt", entry("aaaaaaaaaaaa", 1, 0.0, "欢迎使用字幕学习播放器")))),
                ExtractSourceFile("empty.tags.json", "lesson_中文.srt.tags.json", """{"version":1,"subtitle_file":"lesson_中文.srt","entries":[]}"""),
            ),
            emptyMap(),
        )
        assertTrue(skipped.writes.isEmpty())
        assertEquals(listOf("无法读取", "标签文件无效", "字幕文件名与标签文件不一致", "没有有效内容"), skipped.skips.map { it.reason })
        assertEquals("来源文件夹里没有标签文件", describeExtractResults(false, emptyList(), emptyList()))
        assertTrue(sameExtractFolder("primary:Movies", "primary:Movies"))
        assertFalse(sameExtractFolder("primary:Movies", "primary:Movies/out"))
        assertTrue(extractSourceInsideDestination("primary:Movies/out/a.tags.json", "primary:Movies", "primary:Movies/out"))
        assertFalse(extractSourceInsideDestination("primary:Movies/a.tags.json", "primary:Movies", "primary:Movies/out"))
    }

    private fun doc(subtitleFile: String, vararg entries: TagEntry) =
        TagDocument(1, subtitleFile, entries.toList())

    private fun source(fileName: String, document: TagDocument) =
        ExtractSourceFile(fileName, fileName, encodeTagDocument(document))
}
