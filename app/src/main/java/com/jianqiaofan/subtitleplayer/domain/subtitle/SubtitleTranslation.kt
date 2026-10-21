package com.jianqiaofan.subtitleplayer.domain.subtitle

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue

/**
 * Languages that can be stored inside a subtitle cue.
 * Add a row here when a new language is needed; callers should use this list
 * instead of hard-coding Chinese, English, and Japanese.
 */
enum class SubtitleLanguage(
    val code: String,
    val label: String,
    val marker: String,
    val modelName: String,
) {
    Zh("zh", "中文", "【中译】", "简体中文"),
    En("en", "英文", "【英译】", "英文"),
    Ja("ja", "日语", "【日译】", "日语"),
}

const val CAPTION_ORIGINAL = "original"
const val TRANSLATING_PLACEHOLDER = "请稍等，字幕正在翻译中"
const val TRANSLATION_BATCH_LIMIT = 40
const val SOURCE_DETECT_LIMIT = 40
const val TRANSLATION_STATUS_UNCONFIGURED = "翻译：未配置 DeepSeek"
const val TRANSLATION_STATUS_NOT_WRITTEN = "翻译：这一组没有写回"

fun translatingStatus(count: Int): String = "翻译：正在翻译 $count 句"

fun translationFailureStatus(message: String): String {
    val text = message.trim().ifEmpty { "翻译失败" }
    val clipped = if (text.length > 240) text.take(240) + "…" else text
    return "翻译：$clipped"
}

data class DeepSeekConfig(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_DEEPSEEK_BASE_URL,
    val model: String = DEFAULT_DEEPSEEK_MODEL,
)

const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"

fun isDeepSeekConfigured(config: DeepSeekConfig): Boolean {
    val key = config.apiKey.trim()
    if (key.isEmpty()) return false
    if (key.startsWith("请") && "填写" in key) return false
    if (key.startsWith("<") && key.endsWith(">")) return false
    if (config.baseUrl.isBlank() || config.model.isBlank()) return false
    return true
}

fun subtitleLanguageByCode(code: String): SubtitleLanguage? =
    SubtitleLanguage.entries.firstOrNull { it.code == code }

data class CueBody(
    val original: String,
    val translations: Map<SubtitleLanguage, String>,
)

fun subtitleOriginal(text: String): String = parseCueBody(text).original

fun parseCueBody(text: String): CueBody {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val originalLines = mutableListOf<String>()
    val translations = linkedMapOf<SubtitleLanguage, String>()
    var current: SubtitleLanguage? = null
    val bucket = mutableListOf<String>()

    fun flush() {
        val language = current ?: return
        translations[language] = bucket.joinToString("\n").trim()
        bucket.clear()
    }

    for (line in lines) {
        val marker = SubtitleLanguage.entries.firstOrNull { it.marker == line.trim() }
        if (marker != null) {
            flush()
            current = marker
            continue
        }
        if (current == null) originalLines += line else bucket += line
    }
    flush()
    return CueBody(originalLines.joinToString("\n").trim(), translations)
}

fun composeCueBody(original: String, translations: Map<SubtitleLanguage, String>): String {
    val chunks = mutableListOf<String>()
    val body = original.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (body.isNotEmpty()) chunks += body
    for ((language, translation) in translations) {
        val value = translation.trim()
        if (value.isEmpty()) continue
        chunks += language.marker
        chunks += value
    }
    return chunks.joinToString("\n")
}

fun withTranslation(text: String, language: SubtitleLanguage, translation: String): String {
    val parsed = parseCueBody(text)
    val next = parsed.translations.toMutableMap()
    val cleaned = translation.trim()
    if (cleaned.isEmpty()) next.remove(language) else next[language] = cleaned
    return composeCueBody(parsed.original, next)
}

fun detectSourceLanguage(texts: Iterable<String>): SubtitleLanguage {
    var kana = 0
    var latin = 0
    var han = 0
    var seen = 0
    for (raw in texts) {
        val text = subtitleOriginal(raw)
        if (text.isBlank()) continue
        if (seen >= SOURCE_DETECT_LIMIT) break
        seen++
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> kana++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
                code in 0x4E00..0x9FFF -> han++
            }
        }
    }
    if (kana >= 2) return SubtitleLanguage.Ja
    if (latin >= 10 && latin > han) return SubtitleLanguage.En
    return SubtitleLanguage.Zh
}

fun detectSourceLanguage(cues: List<SubtitleCue>): SubtitleLanguage =
    detectSourceLanguage(cues.map { it.text })

data class CaptionOption(val code: String, val label: String)

fun captionOptions(source: SubtitleLanguage): List<CaptionOption> =
    listOf(CaptionOption(CAPTION_ORIGINAL, "原文")) +
        SubtitleLanguage.entries
            .filter { it != source }
            .map { CaptionOption(it.code, it.label) }

/** Null means the picture should show the original, and nothing should be translated. */
fun activeTranslationLanguage(storedCode: String, source: SubtitleLanguage): SubtitleLanguage? {
    if (storedCode.isBlank() || storedCode == CAPTION_ORIGINAL) return null
    val language = subtitleLanguageByCode(storedCode) ?: return null
    if (language == source) return null
    return language
}

fun captionSelectionLabel(storedCode: String, source: SubtitleLanguage): String =
    activeTranslationLanguage(storedCode, source)?.label ?: "原文"

fun onScreenCaption(raw: String, target: SubtitleLanguage?): String {
    val body = parseCueBody(raw)
    if (target == null) return body.original
    val translated = body.translations[target]
    if (!translated.isNullOrBlank()) return translated
    if (body.original.isBlank()) return ""
    return TRANSLATING_PLACEHOLDER
}

fun translationFieldsForEdit(raw: String, target: SubtitleLanguage?): List<Pair<SubtitleLanguage, String>> {
    val body = parseCueBody(raw)
    val rows = body.translations.map { (language, value) -> language to value }.toMutableList()
    if (target != null && rows.none { it.first == target }) rows += target to ""
    return rows
}

fun cueNeedsTranslation(text: String, language: SubtitleLanguage): Boolean {
    if (subtitleOriginal(text).isBlank()) return false
    return parseCueBody(text).translations[language].isNullOrBlank()
}

/** First cue that has not ended. Past the last cue, stop. */
fun translationStartIndex(cues: List<SubtitleCue>, seconds: Double): Int? {
    cues.forEachIndexed { index, cue ->
        if (seconds <= cue.end) return index
    }
    return null
}

fun cuesToTranslate(
    cues: List<SubtitleCue>,
    fromIndex: Int,
    language: SubtitleLanguage,
    limit: Int = TRANSLATION_BATCH_LIMIT,
): List<Int> {
    if (fromIndex !in cues.indices || limit <= 0) return emptyList()
    val picked = ArrayList<Int>(limit)
    for (index in fromIndex until cues.size) {
        if (!cueNeedsTranslation(cues[index].text, language)) continue
        picked += index
        if (picked.size >= limit) break
    }
    return picked
}

fun translationBatchKey(listIndex: Int, original: String): String = "$listIndex\t$original"

data class TranslationRequestLine(
    val cueIndex: Int,
    val listIndex: Int,
    val original: String,
)

fun translationSystemPrompt(language: SubtitleLanguage): String =
    "你是字幕翻译。请把每条字幕译成${language.modelName}。\n" +
        "规则：\n" +
        "1. 每条单独翻译，不要合并、拆开、跳过或调换顺序。\n" +
        "2. 只输出译文，不要解释，不要加上【中译】【英译】【日译】这类标记。\n" +
        "3. 每一行的格式为：序号、制表符、译文。序号必须与输入一致。\n" +
        "4. 一条译文只占一行，译文里面不要换行。\n" +
        "5. 用自然、简短的字幕用语，不要增删信息。"

fun singleLineSource(text: String): String =
    text.replace("\r\n", "\n").replace('\n', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

fun translationUserPrompt(lines: List<TranslationRequestLine>): String =
    lines.mapIndexed { slot, line -> "${slot + 1}\t${singleLineSource(line.original)}" }.joinToString("\n")

fun stripTranslationFence(raw: String): String {
    var text = raw.trim()
    if (!text.startsWith("```")) return text
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return ""
    text = text.substring(firstBreak + 1)
    if (text.trimEnd().endsWith("```")) {
        text = text.trimEnd().removeSuffix("```")
    }
    return text.trim()
}

private val numberedTranslationLine = Regex("""^(\d+)(?:\t|[.、:：]|\s+)\s*(.+)$""")

/** Null when any requested number is missing or empty. Extra lines are ignored. */
fun parseTranslationLines(raw: String, expectedIndices: List<Int>): Map<Int, String>? {
    val expected = expectedIndices.toSet()
    val found = linkedMapOf<Int, String>()
    for (rawLine in stripTranslationFence(raw).replace("\r\n", "\n").split('\n')) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("```")) continue
        val match = numberedTranslationLine.matchEntire(line) ?: continue
        val number = match.groupValues[1].toIntOrNull() ?: continue
        val body = cleanTranslationBody(match.groupValues[2])
        if (number in expected && body.isNotEmpty()) found[number] = body
    }
    if (found.keys != expected) return null
    return found
}

private fun cleanTranslationBody(text: String): String =
    text.replace("\r\n", "\n").split('\n')
        .filter { line -> SubtitleLanguage.entries.none { it.marker == line.trim() } }
        .joinToString("\n")
        .trim()

/**
 * Ask once. If the whole group does not line up, ask each half once and drop
 * any half that still does not line up. Network errors are left to the caller.
 */
fun requestTranslations(
    lines: List<TranslationRequestLine>,
    language: SubtitleLanguage,
    request: (system: String, user: String) -> String,
): Map<Int, String> {
    if (lines.isEmpty()) return emptyMap()
    fun once(group: List<TranslationRequestLine>): Map<Int, String>? {
        val pending = group.filter { singleLineSource(it.original).isNotEmpty() }
        if (pending.isEmpty()) return emptyMap()
        val raw = request(translationSystemPrompt(language), translationUserPrompt(pending))
        val parsed = parseTranslationLines(raw, pending.indices.map { it + 1 }) ?: return null
        return pending.mapIndexed { slot, line -> line.listIndex to parsed.getValue(slot + 1) }.toMap()
    }
    once(lines)?.let { return it }
    if (lines.size <= 1) return emptyMap()
    val mid = lines.size / 2
    return once(lines.subList(0, mid)).orEmpty() + once(lines.subList(mid, lines.size)).orEmpty()
}

data class TranslationApplyResult(
    val cues: List<SubtitleCue>,
    val written: Int,
    val skippedKeys: Set<String>,
)

/**
 * Write only the target language, and only where the original is still the
 * text that was sent. Blank model output and a changed original are skipped.
 */
fun applyTranslations(
    cues: List<SubtitleCue>,
    language: SubtitleLanguage,
    sent: List<TranslationRequestLine>,
    returned: Map<Int, String>,
): TranslationApplyResult {
    val next = cues.toMutableList()
    val skipped = mutableSetOf<String>()
    var written = 0
    for (line in sent) {
        val key = translationBatchKey(line.listIndex, line.original)
        val cue = next.getOrNull(line.listIndex)
        val translation = returned[line.listIndex]
        if (cue == null || translation == null) {
            skipped += key
            continue
        }
        if (subtitleOriginal(cue.text) != line.original) continue
        if (!parseCueBody(cue.text).translations[language].isNullOrBlank()) continue
        if (translation.isBlank()) {
            skipped += key
            continue
        }
        next[line.listIndex] = cue.copy(text = withTranslation(cue.text, language, translation))
        written++
    }
    return TranslationApplyResult(next, written, skipped)
}
