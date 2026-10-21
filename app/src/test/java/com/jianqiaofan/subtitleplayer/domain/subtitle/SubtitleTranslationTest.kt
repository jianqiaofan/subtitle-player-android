package com.jianqiaofan.subtitleplayer.domain.subtitle

import com.jianqiaofan.subtitleplayer.domain.display.joinSelectedCueTexts
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleFormat
import com.jianqiaofan.subtitleplayer.domain.tags.TagEdit
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.alignTags
import com.jianqiaofan.subtitleplayer.domain.tags.applyTagEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationTest {
    @Test
    fun parseKeepsOriginalAndOneBlockPerLanguage() {
        val raw = "How are you?\n【中译】\n你好吗？\n【日译】\n元気？\n【中译】\n你好"
        val body = parseCueBody(raw)
        assertEquals("How are you?", body.original)
        assertEquals("你好", body.translations[SubtitleLanguage.Zh])
        assertEquals("元気？", body.translations[SubtitleLanguage.Ja])
        assertEquals("How are you?\n【中译】\n你好\n【日译】\n元気？", composeCueBody(body.original, body.translations))
    }

    @Test
    fun writingOneLanguageLeavesTheOthers() {
        val raw = "How are you?\n【中译】\n你好吗？"
        val next = withTranslation(raw, SubtitleLanguage.Ja, "元気？")
        assertEquals("How are you?\n【中译】\n你好吗？\n【日译】\n元気？", next)
        assertEquals("How are you?\n【日译】\n元気？", withTranslation(next, SubtitleLanguage.Zh, "  "))
        assertEquals(next, withTranslation(next, SubtitleLanguage.Ja, "元気？"))
        val ordered = "Hello\n【日译】\nこんにちは\n【中译】\n你好"
        assertEquals(
            "Hello\n【日译】\nこんにちは\n【中译】\n您好",
            withTranslation(ordered, SubtitleLanguage.Zh, "您好"),
        )
    }

    @Test
    fun listCopyAndRoundTripUseOriginalOnly() {
        val text = "How are you?\n【中译】\n你好吗？"
        val cue = SubtitleCue(1, 0.0, 2.0, text)
        assertEquals("1. [00:00 → 00:02] How are you?", formatCueListLine(cue))
        assertEquals("How are you?", joinSelectedCueTexts(listOf(cue), listOf(0)))
        val file = cuesToFileContent(listOf(cue), SubtitleFormat.Srt)
        val loaded = loadSubtitleContent(file, SubtitleFormat.Srt).single()
        assertEquals("How are you?", subtitleOriginal(loaded.text))
        assertEquals("你好吗？", parseCueBody(loaded.text).translations[SubtitleLanguage.Zh])
    }

    @Test
    fun detectsJapaneseBeforeChineseAndIgnoresTranslations() {
        val japanese = List(3) { "漢字が多いです" } + List(50) { "This is english text for later cues" }
        assertEquals(SubtitleLanguage.Ja, detectSourceLanguage(japanese))
        val english = List(40) { "How are you today friend" } + listOf("これは日本語です")
        assertEquals(SubtitleLanguage.En, detectSourceLanguage(english))
        assertEquals(SubtitleLanguage.Zh, detectSourceLanguage(listOf("你好，世界", "第二句")))
        val mixed = "Hello there\n【中译】\n你好你好你好你好"
        assertEquals(SubtitleLanguage.En, detectSourceLanguage(listOf(mixed, mixed)))
    }

    @Test
    fun englishSourceOffersChineseAndJapanese() {
        val options = captionOptions(SubtitleLanguage.En)
        assertEquals(listOf("原文", "中文", "日语"), options.map { it.label })
        assertNull(activeTranslationLanguage("en", SubtitleLanguage.En))
        assertEquals("原文", captionSelectionLabel("en", SubtitleLanguage.En))
        assertEquals(SubtitleLanguage.Zh, activeTranslationLanguage("zh", SubtitleLanguage.En))
    }

    @Test
    fun onScreenShowsTranslationWaitingOrBlank() {
        val raw = "How are you?\n【中译】\n你好吗？"
        assertEquals("How are you?", onScreenCaption(raw, null))
        assertEquals("你好吗？", onScreenCaption(raw, SubtitleLanguage.Zh))
        assertEquals(TRANSLATING_PLACEHOLDER, onScreenCaption(raw, SubtitleLanguage.Ja))
        assertEquals("", onScreenCaption("【中译】\n你好", SubtitleLanguage.Ja))
    }

    @Test
    fun editFieldsIncludeMissingDisplayLanguage() {
        val raw = "How are you?\n【中译】\n你好吗？"
        assertEquals(
            listOf(SubtitleLanguage.Zh to "你好吗？", SubtitleLanguage.Ja to ""),
            translationFieldsForEdit(raw, SubtitleLanguage.Ja),
        )
        assertEquals(
            listOf(SubtitleLanguage.Zh to "你好吗？"),
            translationFieldsForEdit(raw, null),
        )
    }

    @Test
    fun batchStartsAtCurrentCueAndSkipsDoneOrEmpty() {
        val cues = listOf(
            cue(0.0, "one"),
            cue(2.0, "two\n【中译】\n二"),
            cue(4.0, "   "),
            cue(6.0, "four"),
        )
        assertEquals(1, translationStartIndex(cues, 3.0))
        assertEquals(listOf(3), cuesToTranslate(cues, 1, SubtitleLanguage.Zh))
        assertNull(translationStartIndex(cues, 9.0))
        val many = (0 until 50).map { cue(it.toDouble(), "line $it") }
        assertEquals((10 until 50).toList(), cuesToTranslate(many, 10, SubtitleLanguage.Zh))
    }

    @Test
    fun responseMustMatchIndicesAndFencesAreRemoved() {
        val lines = listOf(
            TranslationRequestLine(2, 1, "Hello\nthere"),
            TranslationRequestLine(4, 3, "Bye"),
        )
        assertEquals("1\tHello there\n2\tBye", translationUserPrompt(lines))
        val fenced = "```text\n2\t再见\n1\t你好\n```"
        assertEquals(mapOf(1 to "你好", 2 to "再见"), parseTranslationLines(fenced, listOf(1, 2)))
        assertEquals(
            mapOf(1 to "你好", 2 to "再见"),
            parseTranslationLines("说明\n1. 你好\n2、再见", listOf(1, 2)),
        )
        assertNull(parseTranslationLines("1\t你好", listOf(1, 2)))
        val calls = mutableListOf<String>()
        val fetched = requestTranslations(lines, SubtitleLanguage.Zh) { _, user ->
            calls += user
            if (user.contains('\n')) "nope" else "${user.substringBefore('\t')}\t译"
        }
        assertEquals(mapOf(1 to "译", 3 to "译"), fetched)
        assertEquals(3, calls.size)
    }

    @Test
    fun applyDropsEditedOriginalsAndDoesNotTouchOtherLanguages() {
        val cues = listOf(
            SubtitleCue(2, 0.0, 1.0, "Hello\n【日译】\nこんにちは"),
            SubtitleCue(4, 1.0, 2.0, "Changed"),
        )
        val sent = listOf(
            TranslationRequestLine(2, 0, "Hello"),
            TranslationRequestLine(4, 1, "Bye"),
        )
        val applied = applyTranslations(
            cues,
            SubtitleLanguage.Zh,
            sent,
            mapOf(0 to "你好", 1 to "再见"),
        )
        assertEquals(1, applied.written)
        assertEquals("Hello\n【日译】\nこんにちは\n【中译】\n你好", applied.cues[0].text)
        assertEquals("Changed", applied.cues[1].text)
        assertFalse(applied.skippedKeys.contains(translationBatchKey(0, "Hello")))
    }

    @Test
    fun tagsAlignOnOriginalAndAppendKeepsExistingTags() {
        val cues = listOf(
            SubtitleCue(1, 0.0, 1.0, "How are you today?\n【中译】\n你好吗？"),
            SubtitleCue(2, 2.0, 3.0, "See you later friend"),
        )
        val aligned = alignTags(
            cues,
            listOf(TagEntry("aaaaaaaaaaaa", 1, 0.0, 1.0, "How are you today?", listOf("重点"), "备注")),
        )
        assertEquals(listOf("重点"), aligned.attached.getValue(0).tags)
        val appended = applyTagEdit(
            aligned,
            cues,
            TagEdit(listOf(0, 1), listOf("难点"), "新备注", applyNote = false, append = true),
        ) { "bbbbbbbbbbbb" }
        assertEquals(listOf("重点", "难点"), appended.attached.getValue(0).tags)
        assertEquals("备注", appended.attached.getValue(0).note)
        assertEquals("How are you today?", appended.attached.getValue(0).text)
        assertEquals(listOf("难点"), appended.attached.getValue(1).tags)
        val same = applyTagEdit(
            appended,
            cues,
            TagEdit(listOf(0, 1), emptyList(), "不会写入", applyNote = false, append = true),
        )
        assertEquals(appended, same)
    }

    @Test
    fun unconfiguredKeyIsRejected() {
        assertFalse(isDeepSeekConfigured(DeepSeekConfig()))
        assertFalse(isDeepSeekConfigured(DeepSeekConfig(apiKey = "请在此填写 DeepSeek API Key")))
        assertTrue(isDeepSeekConfigured(DeepSeekConfig(apiKey = "sk-test")))
    }

    private fun cue(start: Double, text: String) = SubtitleCue(start.toInt() + 1, start, start + 1.0, text)
}
