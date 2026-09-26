package com.jianqiaofan.subtitleplayer.domain.tags

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.round

const val TAG_FILE_SUFFIX = ".tags.json"
const val TAG_DOCUMENT_VERSION = 1
const val CUSTOM_TAG_MAX_LENGTH = 48
private const val TEXT_CLOSE_MIN_CHARS = 8
private const val TEXT_CLOSE_RATIO = 0.82
private const val START_TOLERANCE_MS = 1L
private const val UNIQUE_TEXT_GAP_SEC = 1.0

val PRESET_TAGS = listOf("重点", "难点", "易错", "跟读", "已掌握")

private val TAG_PRIORITY = listOf("难点", "重点", "易错", "跟读", "已掌握")

data class TagPalette(val background: Long, val foreground: Long)

fun tagPalette(name: String): TagPalette = when (name) {
    "重点" -> TagPalette(0xFF6B5420, 0xFFFFD78A)
    "难点" -> TagPalette(0xFF6B3030, 0xFFFFB0A8)
    "易错" -> TagPalette(0xFF6B4520, 0xFFFFC48A)
    "跟读" -> TagPalette(0xFF1E3D55, 0xFFB9E0FF)
    "已掌握" -> TagPalette(0xFF1E3D32, 0xFF9DDEB8)
    else -> TagPalette(0xFF3A3A3A, 0xFFDDDDDD)
}

/** Left bar color: 难点 > 重点 > 易错 > 跟读 > 已掌握 > first custom tag. */
fun primaryTag(tags: List<String>): String? {
    if (tags.isEmpty()) return null
    for (name in TAG_PRIORITY) {
        if (name in tags) return name
    }
    return tags.firstOrNull { it !in PRESET_TAGS } ?: tags.first()
}

/** Dim body only when 已掌握 is the sole tag. */
fun subtitleBodyDimmed(tags: List<String>): Boolean =
    tags.size == 1 && tags[0] == "已掌握"

data class TagEntry(
    val id: String,
    val index: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val tags: List<String>,
    val note: String,
)

data class TagDocument(
    val version: Int,
    val subtitleFile: String,
    val entries: List<TagEntry>,
)

data class TagAlignment(
    val attached: Map<Int, TagEntry> = emptyMap(),
    val unmatched: List<TagEntry> = emptyList(),
)

data class TagListFilter(
    val selectedTags: Set<String> = emptySet(),
    val showUnmatched: Boolean = false,
) {
    val isAll: Boolean get() = !showUnmatched && selectedTags.isEmpty()
}

sealed class SubtitleListRow {
    data class Cue(val cueIndex: Int) : SubtitleListRow()
    data class Unmatched(val entryId: String) : SubtitleListRow()
}

enum class TagSyncKind { Copied, Merged, Skipped }

data class TagSyncDecision(
    val kind: TagSyncKind,
    val subtitleFileName: String,
    val reason: String? = null,
    val document: TagDocument? = null,
)

data class BatchTagMatch(
    val videoFileName: String,
    val relativeDir: String,
    val subtitleFileName: String,
    val tagFileName: String,
    val hasExistingTag: Boolean,
)

fun tagFileNameFor(subtitleFileName: String): String = subtitleFileName + TAG_FILE_SUFFIX

fun subtitleFileNameFromTagFile(tagFileName: String): String? {
    if (!tagFileName.endsWith(TAG_FILE_SUFFIX, ignoreCase = true)) return null
    val subtitle = tagFileName.dropLast(TAG_FILE_SUFFIX.length)
    return subtitle.ifBlank { null }
}

fun isPresetTag(name: String): Boolean = name in PRESET_TAGS

fun normalizeCustomTagName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > CUSTOM_TAG_MAX_LENGTH || name in PRESET_TAGS) return null
    return name
}

fun orderedTagNames(tags: List<String>): List<String> {
    val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val presets = PRESET_TAGS.filter { it in cleaned }
    val custom = cleaned.filter { it !in PRESET_TAGS }
    return presets + custom
}

fun collectCustomTagNames(documents: List<TagDocument>): List<String> {
    val seen = linkedSetOf<String>()
    for (doc in documents) {
        for (entry in doc.entries) {
            for (tag in entry.tags) {
                if (tag !in PRESET_TAGS && tag.isNotBlank()) seen += tag
            }
        }
    }
    return seen.toList()
}

fun newTagId(random: java.util.Random = java.util.Random()): String {
    val bytes = ByteArray(6)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

fun stripWhitespace(text: String): String = buildString(text.length) {
    for (ch in text) {
        if (!ch.isWhitespace()) append(ch)
    }
}

fun textsAreClose(left: String, right: String): Boolean {
    if (left == right) return true
    val a = stripWhitespace(left)
    val b = stripWhitespace(right)
    if (a.length < TEXT_CLOSE_MIN_CHARS || b.length < TEXT_CLOSE_MIN_CHARS) return false
    return sequenceMatcherRatio(a, b) >= TEXT_CLOSE_RATIO
}

/** difflib.SequenceMatcher.ratio, without the junk-character heuristic. */
fun sequenceMatcherRatio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val matches = countMatchingChars(a, b, 0, a.length, 0, b.length)
    return (2.0 * matches) / (a.length + b.length)
}

fun startsWithinOneMillisecond(leftSeconds: Double, rightSeconds: Double): Boolean {
    val left = round(leftSeconds * 1000.0).toLong()
    val right = round(rightSeconds * 1000.0).toLong()
    return abs(left - right) <= START_TOLERANCE_MS
}

fun alignTags(cues: List<SubtitleCue>, entries: List<TagEntry>): TagAlignment {
    val used = mutableSetOf<Int>()
    val attached = linkedMapOf<Int, TagEntry>()
    var pending = entries

    fun take(predicate: (TagEntry) -> Int?): List<TagEntry> {
        val rest = mutableListOf<TagEntry>()
        for (entry in pending) {
            val hit = predicate(entry)
            if (hit != null && hit !in used) {
                used += hit
                attached[hit] = entry
            } else {
                rest += entry
            }
        }
        return rest
    }

    pending = take { entry ->
        cues.indices.firstOrNull { i ->
            i !in used &&
                startsWithinOneMillisecond(cues[i].start, entry.start) &&
                textsAreClose(cues[i].text, entry.text)
        }
    }
    pending = take { entry ->
        cues.indices.firstOrNull { i ->
            i !in used && cues[i].index == entry.index && textsAreClose(cues[i].text, entry.text)
        }
    }

    val unmatched = mutableListOf<TagEntry>()
    for (entry in pending) {
        val norm = stripWhitespace(entry.text)
        if (norm.isEmpty()) {
            unmatched += entry
            continue
        }
        val same = cues.indices.filter { stripWhitespace(cues[it].text) == norm }
        val hit = when {
            same.isEmpty() -> null
            same.size == 1 -> same[0].takeIf { it !in used }
            else -> {
                val ranked = same.sortedBy { abs(cues[it].start - entry.start) }
                val nearest = ranked[0]
                val second = ranked[1]
                val gap = abs(cues[second].start - entry.start) - abs(cues[nearest].start - entry.start)
                if (gap >= UNIQUE_TEXT_GAP_SEC && nearest !in used) nearest else null
            }
        }
        if (hit != null) {
            used += hit
            attached[hit] = entry
        } else {
            unmatched += entry
        }
    }
    return TagAlignment(attached, unmatched)
}

fun retargetAttachedEntry(entry: TagEntry, cue: SubtitleCue): TagEntry =
    entry.copy(index = cue.index, start = cue.start, end = cue.end, text = cue.text)

fun applyCueEdit(alignment: TagAlignment, cueIndex: Int, updated: SubtitleCue): TagAlignment {
    val entry = alignment.attached[cueIndex] ?: return alignment
    return alignment.copy(attached = alignment.attached + (cueIndex to retargetAttachedEntry(entry, updated)))
}

fun alignmentToDocument(subtitleFileName: String, alignment: TagAlignment): TagDocument? {
    val entries = buildList {
        alignment.attached.toSortedMap().values.forEach { add(it) }
        addAll(alignment.unmatched)
    }.map { it.copy(tags = orderedTagNames(it.tags)) }
        .filter { it.tags.isNotEmpty() || it.note.isNotBlank() }
    if (entries.isEmpty()) return null
    return TagDocument(TAG_DOCUMENT_VERSION, subtitleFileName, entries)
}

data class TagEdit(
    val cueIndices: List<Int>,
    val tags: List<String>,
    val note: String,
    val applyNote: Boolean,
)

fun applyTagEdit(
    alignment: TagAlignment,
    cues: List<SubtitleCue>,
    edit: TagEdit,
    newId: () -> String = { newTagId() },
): TagAlignment {
    val tags = orderedTagNames(edit.tags)
    val attached = alignment.attached.toMutableMap()
    for (cueIndex in edit.cueIndices.distinct()) {
        val cue = cues.getOrNull(cueIndex) ?: continue
        val existing = attached[cueIndex]
        val note = if (edit.applyNote) edit.note else existing?.note.orEmpty()
        if (tags.isEmpty() && note.isBlank()) {
            attached.remove(cueIndex)
        } else if (existing == null) {
            attached[cueIndex] = TagEntry(
                id = newId(),
                index = cue.index,
                start = cue.start,
                end = cue.end,
                text = cue.text,
                tags = tags,
                note = note,
            )
        } else {
            attached[cueIndex] = existing.copy(
                index = cue.index,
                start = cue.start,
                end = cue.end,
                text = cue.text,
                tags = tags,
                note = note,
            )
        }
    }
    return alignment.copy(attached = attached)
}

fun clearTags(alignment: TagAlignment, cueIndices: Collection<Int>): TagAlignment =
    alignment.copy(attached = alignment.attached - cueIndices.toSet())

fun updateNote(alignment: TagAlignment, cueIndex: Int, note: String): TagAlignment {
    val existing = alignment.attached[cueIndex] ?: return alignment
    if (existing.tags.isEmpty() && note.isBlank()) {
        return alignment.copy(attached = alignment.attached - cueIndex)
    }
    return alignment.copy(attached = alignment.attached + (cueIndex to existing.copy(note = note)))
}

fun attachUnmatchedToCue(
    alignment: TagAlignment,
    entryId: String,
    cueIndex: Int,
    cue: SubtitleCue,
    newId: () -> String = { newTagId() },
): TagAlignment {
    val incoming = alignment.unmatched.find { it.id == entryId } ?: return alignment
    val existing = alignment.attached[cueIndex]
    val mergedTags = orderedTagNames(
        if (existing == null) incoming.tags else existing.tags + incoming.tags,
    )
    val mergedNote = when {
        existing == null -> incoming.note
        existing.note.isBlank() -> incoming.note
        else -> existing.note
    }
    if (mergedTags.isEmpty() && mergedNote.isBlank()) {
        return alignment.copy(unmatched = alignment.unmatched.filterNot { it.id == entryId })
    }
    val entry = if (existing == null) {
        incoming.copy(
            id = incoming.id.ifBlank { newId() },
            index = cue.index,
            start = cue.start,
            end = cue.end,
            text = cue.text,
            tags = mergedTags,
            note = mergedNote,
        )
    } else {
        existing.copy(tags = mergedTags, note = mergedNote, index = cue.index, start = cue.start, end = cue.end, text = cue.text)
    }
    return alignment.copy(
        attached = alignment.attached + (cueIndex to entry),
        unmatched = alignment.unmatched.filterNot { it.id == entryId },
    )
}

fun deleteUnmatched(alignment: TagAlignment, entryId: String): TagAlignment =
    alignment.copy(unmatched = alignment.unmatched.filterNot { it.id == entryId })

fun tagCounts(alignment: TagAlignment): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    for (entry in alignment.attached.values) {
        for (tag in entry.tags.distinct()) {
            counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    return counts
}

fun filterButtons(counts: Map<String, Int>): List<Pair<String, Int>> {
    val presets = PRESET_TAGS.mapNotNull { name -> counts[name]?.let { name to it } }
    val custom = counts.filterKeys { it !in PRESET_TAGS }.map { it.key to it.value }
    return presets + custom
}

fun shouldShowTagFilter(alignment: TagAlignment): Boolean =
    alignment.attached.values.any { it.tags.isNotEmpty() } || alignment.unmatched.isNotEmpty()

fun toggleTagFilter(current: TagListFilter, tag: String): TagListFilter {
    val selected = if (current.showUnmatched) {
        setOf(tag)
    } else if (tag in current.selectedTags) {
        current.selectedTags - tag
    } else {
        current.selectedTags + tag
    }
    return TagListFilter(selectedTags = selected, showUnmatched = false)
}

fun buildListRows(
    cueCount: Int,
    alignment: TagAlignment,
    filter: TagListFilter,
): List<SubtitleListRow> {
    if (filter.showUnmatched) {
        return alignment.unmatched.map { SubtitleListRow.Unmatched(it.id) }
    }
    return (0 until cueCount).mapNotNull { index ->
        if (cueVisible(alignment.attached[index]?.tags.orEmpty(), filter)) {
            SubtitleListRow.Cue(index)
        } else {
            null
        }
    }
}

fun cueVisible(tags: List<String>, filter: TagListFilter): Boolean {
    if (filter.showUnmatched) return false
    if (filter.selectedTags.isEmpty()) return true
    return tags.any { it in filter.selectedTags }
}

fun releaseFilterIfNoTags(alignment: TagAlignment, filter: TagListFilter): TagListFilter {
    if (shouldShowTagFilter(alignment)) {
        if (filter.showUnmatched) return filter
        val remaining = filter.selectedTags.filter { tag ->
            alignment.attached.values.any { tag in it.tags }
        }.toSet()
        return filter.copy(selectedTags = remaining)
    }
    return TagListFilter()
}

fun adjacentTaggedCue(
    cues: List<SubtitleCue>,
    alignment: TagAlignment,
    filter: TagListFilter,
    currentCueIndex: Int,
    forward: Boolean,
): Int? {
    if (filter.showUnmatched) return null
    val eligible = cues.indices.filter { index ->
        val tags = alignment.attached[index]?.tags.orEmpty()
        if (tags.isEmpty()) return@filter false
        if (filter.selectedTags.isEmpty()) true else tags.any { it in filter.selectedTags }
    }
    if (eligible.isEmpty()) return null
    return if (forward) {
        eligible.firstOrNull { it > currentCueIndex }
    } else {
        eligible.lastOrNull { it < currentCueIndex }
    }
}

fun adjacentUnmatched(
    unmatched: List<TagEntry>,
    positionSec: Double,
    forward: Boolean,
): TagEntry? {
    val ordered = unmatched.sortedWith(compareBy({ it.start }, { it.id }))
    if (ordered.isEmpty()) return null
    val anchor = ordered.indexOfLast { it.start <= positionSec + 0.0005 }
    if (forward) {
        val next = if (anchor < 0) 0 else anchor + 1
        return ordered.getOrNull(next)
    }
    if (anchor < 0) return null
    val current = ordered[anchor]
    val onCurrent = abs(current.start - positionSec) <= 0.05
    return if (onCurrent) ordered.getOrNull(anchor - 1) else current
}

fun mergeTagNames(local: List<String>, incoming: List<String>): List<String> =
    orderedTagNames(local + incoming)

fun mergeNotes(local: String, incoming: String): String {
    if (local.isBlank()) return incoming
    if (incoming.isBlank() || local == incoming) return local
    if (incoming in local) return local
    return local + "\n" + incoming
}

fun mergeTagEntries(local: List<TagEntry>, incoming: List<TagEntry>): List<TagEntry> {
    val merged = local.toMutableList()
    val used = mutableSetOf<Int>()
    for (entry in incoming) {
        val index = findMergeTarget(merged, entry, used)
        if (index == null) {
            val tags = orderedTagNames(entry.tags)
            if (tags.isNotEmpty() || entry.note.isNotBlank()) merged += entry.copy(tags = tags)
        } else {
            used += index
            val base = merged[index]
            merged[index] = base.copy(
                tags = mergeTagNames(base.tags, entry.tags),
                note = mergeNotes(base.note, entry.note),
            )
        }
    }
    return merged.filter { it.tags.isNotEmpty() || it.note.isNotBlank() }
}

fun planTagSync(
    tagFileName: String,
    parsed: TagDocument?,
    destinationSubtitleExists: Boolean,
    destinationTag: TagDocument?,
    sourceIsDestination: Boolean,
): TagSyncDecision {
    val subtitleName = subtitleFileNameFromTagFile(tagFileName)
        ?: return TagSyncDecision(TagSyncKind.Skipped, tagFileName, "文件名不是字幕标签文件")
    if (!destinationSubtitleExists) {
        return TagSyncDecision(TagSyncKind.Skipped, subtitleName, "当前文件夹没有同名字幕")
    }
    if (parsed == null || parsed.subtitleFile != subtitleName || parsed.entries.isEmpty()) {
        return TagSyncDecision(TagSyncKind.Skipped, subtitleName, "标签文件无效或没有可用条目")
    }
    if (sourceIsDestination) {
        return TagSyncDecision(TagSyncKind.Skipped, subtitleName, "源文件就是目标标签文件")
    }
    if (destinationTag == null) {
        return TagSyncDecision(
            TagSyncKind.Copied,
            subtitleName,
            document = parsed.copy(version = TAG_DOCUMENT_VERSION, subtitleFile = subtitleName),
        )
    }
    val combined = mergeTagEntries(destinationTag.entries, parsed.entries)
    return TagSyncDecision(
        TagSyncKind.Merged,
        subtitleName,
        document = TagDocument(TAG_DOCUMENT_VERSION, subtitleName, combined),
    )
}

fun videoMatchesSubtitle(videoFileName: String, subtitleFileName: String): Boolean {
    val videoStem = videoFileName.substringBeforeLast('.', missingDelimiterValue = videoFileName)
    val subtitleStem = subtitleFileName.substringBeforeLast('.', missingDelimiterValue = subtitleFileName)
    return subtitleStem == videoStem || subtitleStem.startsWith("${videoStem}_")
}

/**
 * filesByDir: relative directory ("" for the chosen root) -> file names in that directory.
 * One row per matching video. The subtitle file must sit in the same directory.
 */
fun matchBatchTargets(
    tagFileName: String,
    document: TagDocument?,
    filesByDir: Map<String, List<String>>,
): List<BatchTagMatch> {
    val subtitleName = subtitleFileNameFromTagFile(tagFileName) ?: return emptyList()
    if (document == null || document.subtitleFile != subtitleName || document.entries.isEmpty()) {
        return emptyList()
    }
    val rows = mutableListOf<BatchTagMatch>()
    for ((dir, names) in filesByDir) {
        if (subtitleName !in names) continue
        val tagName = tagFileNameFor(subtitleName)
        val hasTag = tagName in names
        for (video in names) {
            if (!isMediaFile(video) || !videoMatchesSubtitle(video, subtitleName)) continue
            rows += BatchTagMatch(
                videoFileName = video,
                relativeDir = dir,
                subtitleFileName = subtitleName,
                tagFileName = tagFileName,
                hasExistingTag = hasTag,
            )
        }
    }
    return rows
}

fun parseTagDocument(raw: String, expectedSubtitleFile: String? = null): TagDocument? {
    val text = raw.removePrefix("\uFEFF").trim()
    if (text.isEmpty()) return null
    val root = try {
        JsonParser(text).parse() as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    val subtitleFile = (root.map["subtitle_file"] as? JsonValue.Str)?.value ?: return null
    if (expectedSubtitleFile != null && subtitleFile != expectedSubtitleFile) return null
    val version = when (val v = root.map["version"]) {
        is JsonValue.Num -> v.value.toInt()
        null -> TAG_DOCUMENT_VERSION
        else -> return null
    }
    val entriesNode = root.map["entries"] as? JsonValue.Arr ?: return null
    val entries = entriesNode.items.mapNotNull { node -> parseEntry(node as? JsonValue.Obj ?: return@mapNotNull null) }
    return TagDocument(version, subtitleFile, entries)
}

fun encodeTagDocument(document: TagDocument): String = buildString {
    append("{\n")
    append("  \"version\": ${document.version},\n")
    append("  \"subtitle_file\": ${jsonString(document.subtitleFile)},\n")
    append("  \"entries\": [\n")
    document.entries.forEachIndexed { index, entry ->
        append("    {\n")
        append("      \"id\": ${jsonString(entry.id)},\n")
        append("      \"index\": ${entry.index},\n")
        append("      \"start\": ${jsonNumber(entry.start)},\n")
        append("      \"end\": ${jsonNumber(entry.end)},\n")
        append("      \"text\": ${jsonString(entry.text)},\n")
        append("      \"tags\": [${entry.tags.joinToString(", ") { jsonString(it) }}],\n")
        append("      \"note\": ${jsonString(entry.note)}\n")
        append("    }")
        if (index != document.entries.lastIndex) append(",")
        append("\n")
    }
    append("  ]\n")
    append("}\n")
}

private fun parseEntry(obj: JsonValue.Obj): TagEntry? {
    val index = (obj.map["index"] as? JsonValue.Num)?.value?.toInt() ?: return null
    val start = (obj.map["start"] as? JsonValue.Num)?.value ?: return null
    val end = (obj.map["end"] as? JsonValue.Num)?.value ?: return null
    val text = (obj.map["text"] as? JsonValue.Str)?.value ?: return null
    val id = ((obj.map["id"] as? JsonValue.Str)?.value).orEmpty().ifBlank { newTagId() }
    val tags = when (val node = obj.map["tags"]) {
        is JsonValue.Arr -> orderedTagNames(node.items.mapNotNull { (it as? JsonValue.Str)?.value })
        null -> emptyList()
        else -> return null
    }
    val note = when (val node = obj.map["note"]) {
        is JsonValue.Str -> node.value
        null -> ""
        else -> return null
    }
    if (tags.isEmpty() && note.isBlank()) return null
    return TagEntry(id, index, start, end, text, tags, note)
}

fun describeSyncResults(results: List<TagSyncDecision>): String {
    fun block(title: String, items: List<TagSyncDecision>, line: (TagSyncDecision) -> String): String {
        if (items.isEmpty()) return ""
        return title + "\n" + items.joinToString("\n", transform = line)
    }
    return listOf(
        block("已复制", results.filter { it.kind == TagSyncKind.Copied }) { it.subtitleFileName },
        block("已合并", results.filter { it.kind == TagSyncKind.Merged }) { it.subtitleFileName },
        block("已跳过", results.filter { it.kind == TagSyncKind.Skipped }) {
            "${it.subtitleFileName}（${it.reason ?: "跳过"}）"
        },
    ).filter { it.isNotEmpty() }.joinToString("\n\n").ifBlank { "没有需要同步的标签文件" }
}

private fun findMergeTarget(local: List<TagEntry>, incoming: TagEntry, used: Set<Int>): Int? {
    if (incoming.id.isNotBlank()) {
        val byId = local.indices.firstOrNull { it !in used && local[it].id == incoming.id }
        if (byId != null) return byId
    }
    val byTime = local.indices.firstOrNull { i ->
        i !in used && startsWithinOneMillisecond(local[i].start, incoming.start) && textsAreClose(local[i].text, incoming.text)
    }
    if (byTime != null) return byTime
    val byIndex = local.indices.firstOrNull { i ->
        i !in used && local[i].index == incoming.index && textsAreClose(local[i].text, incoming.text)
    }
    if (byIndex != null) return byIndex
    val norm = stripWhitespace(incoming.text)
    if (norm.isEmpty()) return null
    val same = local.indices.filter { stripWhitespace(local[it].text) == norm }
    if (same.size != 1) return null
    val only = same[0]
    return only.takeIf { it !in used }
}

private fun countMatchingChars(a: String, b: String, alo: Int, ahi: Int, blo: Int, bhi: Int): Int {
    val (i, j, size) = findLongestMatch(a, b, alo, ahi, blo, bhi)
    if (size == 0) return 0
    return size +
        countMatchingChars(a, b, alo, i, blo, j) +
        countMatchingChars(a, b, i + size, ahi, j + size, bhi)
}

private fun findLongestMatch(
    a: String,
    b: String,
    alo: Int,
    ahi: Int,
    blo: Int,
    bhi: Int,
): Triple<Int, Int, Int> {
    val b2j = HashMap<Char, MutableList<Int>>()
    for (j in blo until bhi) {
        b2j.getOrPut(b[j]) { mutableListOf() }.add(j)
    }
    var bestI = alo
    var bestJ = blo
    var bestSize = 0
    var j2len = HashMap<Int, Int>()
    for (i in alo until ahi) {
        val newJ2len = HashMap<Int, Int>()
        for (j in b2j[a[i]].orEmpty()) {
            if (j < blo) continue
            if (j >= bhi) break
            val k = (j2len[j - 1] ?: 0) + 1
            newJ2len[j] = k
            if (k > bestSize) {
                bestI = i - k + 1
                bestJ = j - k + 1
                bestSize = k
            }
        }
        j2len = newJ2len
    }
    return Triple(bestI, bestJ, bestSize)
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

private fun jsonNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
}

private class JsonParser(private val text: String) {
    private var i = 0

    fun parse(): JsonValue {
        skip()
        val value = readValue()
        skip()
        if (i != text.length) error("trailing")
        return value
    }

    private fun readValue(): JsonValue {
        skip()
        return when (val ch = peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            else -> if (ch == '-' || ch.isDigit()) readNumber() else error("value")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val map = linkedMapOf<String, JsonValue>()
        skip()
        if (peek() == '}') {
            i++
            return JsonValue.Obj(map)
        }
        while (true) {
            skip()
            val key = readString()
            skip()
            expect(':')
            map[key] = readValue()
            skip()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Obj(map)
                }
                else -> error("object")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skip()
        if (peek() == ']') {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += readValue()
            skip()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Arr(items)
                }
                else -> error("array")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val ch = text[i++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = text[i++]
                    when (esc) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(i, i + 4)
                            i += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> error("escape")
                    }
                }
                else -> out.append(ch)
            }
        }
        error("string")
    }

    private fun readNumber(): JsonValue.Num {
        val start = i
        if (peek() == '-') i++
        while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            while (i < text.length && text[i].isDigit()) i++
        }
        return JsonValue.Num(text.substring(start, i).toDouble())
    }

    private fun skip() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun peek(): Char = text.getOrNull(i) ?: error("eof")

    private fun expect(ch: Char) {
        if (peek() != ch) error("expected $ch")
        i++
    }
}
