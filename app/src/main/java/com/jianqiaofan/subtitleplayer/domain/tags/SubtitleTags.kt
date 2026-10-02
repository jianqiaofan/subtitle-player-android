package com.jianqiaofan.subtitleplayer.domain.tags

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.jsonNumber
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.time.isNewerOrEqualUtc
import com.jianqiaofan.subtitleplayer.domain.time.utcTimestamp
import kotlin.math.abs
import kotlin.math.round

const val TAG_FILE_SUFFIX = ".tags.json"
const val TAG_DOCUMENT_VERSION = 1
const val CUSTOM_TAG_MAX_LENGTH = 48
private const val TEXT_CLOSE_MIN_CHARS = 8
private const val TEXT_CLOSE_RATIO = 0.82
private const val START_TOLERANCE_MS = 1L
private const val UNIQUE_TEXT_GAP_SEC = 1.0

val TAG_CATEGORIES = listOf(
    // 「还没想好」must stay last in 通用.
    "通用" to listOf("重点", "难点", "易错", "新章节", "新页面", "重要断点", "已掌握", "待复习", "存疑", "还没想好"),
    "备考" to listOf("真题", "案例", "考前扫一眼", "技巧", "必背", "口诀"),
    "语言学习" to listOf("单词", "语法", "发音", "短语", "地道表达"),
    "电影" to listOf("佳句", "反复练听", "跟读", "长难句", "俚语", "文化背景", "名场面"),
)

val PRESET_TAGS = TAG_CATEGORIES.flatMap { it.second }

private val TAG_PRIORITY = listOf(
    "存疑",
    "难点",
    "易错",
    "重点",
    "待复习",
    "长难句",
    "反复练听",
    "跟读",
    "真题",
    "案例",
    "考前扫一眼",
    "必背",
    "单词",
    "语法",
    "发音",
    "短语",
    "地道表达",
    "佳句",
    "俚语",
    "技巧",
    "口诀",
    "文化背景",
    "名场面",
    "新章节",
    "新页面",
    "重要断点",
    "已掌握",
)

data class TagPalette(val background: Long, val foreground: Long)

fun tagPalette(name: String): TagPalette = when (name) {
    "重点" -> TagPalette(0xFF6B5420, 0xFFFFD78A)
    "难点" -> TagPalette(0xFF6B3030, 0xFFFFB0A8)
    "易错" -> TagPalette(0xFF6B4520, 0xFFFFC48A)
    "新章节" -> TagPalette(0xFF1E4A48, 0xFF9EE0D6)
    "新页面" -> TagPalette(0xFF1E3F4A, 0xFF9ED4E0)
    "重要断点" -> TagPalette(0xFF4A3A1E, 0xFFF0D090)
    "已掌握" -> TagPalette(0xFF1E3D32, 0xFF9DDEB8)
    "待复习" -> TagPalette(0xFF5A3A1E, 0xFFFFCC88)
    "存疑" -> TagPalette(0xFF5A2048, 0xFFFFB0D0)
    "还没想好" -> TagPalette(0xFF3A3A42, 0xFFD0D0D8)
    "真题" -> TagPalette(0xFF5C2840, 0xFFFFB3C7)
    "案例" -> TagPalette(0xFF3D4A28, 0xFFD5E8A8)
    "考前扫一眼", "得分点" -> TagPalette(0xFF6B3A28, 0xFFFFC2A8)
    "技巧" -> TagPalette(0xFF4A3820, 0xFFF0D0A0)
    "必背" -> TagPalette(0xFF6B2848, 0xFFFFB0C8)
    "口诀" -> TagPalette(0xFF5A4030, 0xFFF5D0B0)
    "单词" -> TagPalette(0xFF243A5C, 0xFFB9D0FF)
    "语法" -> TagPalette(0xFF2A3058, 0xFFC4C0FF)
    "发音" -> TagPalette(0xFF1E4558, 0xFFA8E4FF)
    "短语" -> TagPalette(0xFF243858, 0xFFC8D8FF)
    "地道表达" -> TagPalette(0xFF30305A, 0xFFD0C8FF)
    "佳句" -> TagPalette(0xFF3A3058, 0xFFE0C8FF)
    "反复练听" -> TagPalette(0xFF1A4550, 0xFFA8F0E0)
    "跟读" -> TagPalette(0xFF1E3D55, 0xFFB9E0FF)
    "长难句" -> TagPalette(0xFF3A2848, 0xFFE8B8E0)
    "俚语" -> TagPalette(0xFF4A3040, 0xFFFFC0D8)
    "文化背景" -> TagPalette(0xFF3A4030, 0xFFE0E8B0)
    "名场面" -> TagPalette(0xFF4A2840, 0xFFFFC0B0)
    else -> TagPalette(0xFF3A3A3A, 0xFFDDDDDD)
}

/** Left bar color follows preset priority, then the first custom tag. */
fun primaryTag(tags: List<String>): String? {
    if (tags.isEmpty()) return null
    val ordered = orderedTagNames(tags)
    for (name in TAG_PRIORITY) {
        if (name in ordered) return name
    }
    return ordered.firstOrNull { it !in PRESET_TAGS }
}

/** Dim body only when 已掌握 is the sole tag. */
fun subtitleBodyDimmed(tags: List<String>): Boolean =
    tags.size == 1 && tags[0] == "已掌握"

data class TagOp(
    val name: String,
    val present: Boolean,
    val at: String,
)

data class TagEntry(
    val id: String,
    val index: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val tags: List<String>,
    val note: String,
    val tagOps: List<TagOp> = emptyList(),
    val noteAt: String = "",
)

fun tagNow(): String = utcTimestamp()

fun latestTagOps(ops: List<TagOp>): Map<String, TagOp> {
    val latest = linkedMapOf<String, TagOp>()
    for (op in ops) {
        val name = op.name.trim()
        if (name.isEmpty()) continue
        val prev = latest[name]
        if (prev == null || isNewerOrEqualUtc(op.at, prev.at)) latest[name] = op.copy(name = name)
    }
    return latest
}

fun visibleTagNames(ops: List<TagOp>): List<String> =
    orderedTagNames(latestTagOps(ops).filterValues { it.present }.keys.toList())

/** Tags array stays for older files. Ops override the names they mention. */
fun resolveVisibleTags(tags: List<String>, ops: List<TagOp>): List<String> {
    if (ops.isEmpty()) return orderedTagNames(tags)
    val latest = latestTagOps(ops)
    val names = orderedTagNames(tags).toMutableSet()
    for ((name, op) in latest) {
        if (op.present) names += name else names -= name
    }
    return orderedTagNames(names.toList())
}

fun reviseTagOps(existing: List<TagOp>, before: List<String>, after: List<String>, at: String): List<TagOp> {
    val ops = existing.toMutableList()
    val beforeSet = before.toSet()
    val afterSet = after.toSet()
    for (name in after) {
        if (name !in beforeSet) ops += TagOp(name, true, at)
    }
    for (name in before) {
        if (name !in afterSet) ops += TagOp(name, false, at)
    }
    return ops
}

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
    if (raw.any { it == '\n' || it == '\r' || it == '\t' }) return null
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
        .filter { it.tags.isNotEmpty() || it.note.isNotBlank() || it.tagOps.isNotEmpty() }
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
    at: String = tagNow(),
    newId: () -> String = { newTagId() },
): TagAlignment {
    val tags = orderedTagNames(edit.tags)
    val attached = alignment.attached.toMutableMap()
    for (cueIndex in edit.cueIndices.distinct()) {
        val cue = cues.getOrNull(cueIndex) ?: continue
        val existing = attached[cueIndex]
        val note = if (edit.applyNote) edit.note else existing?.note.orEmpty()
        val noteAt = when {
            existing == null -> if (note.isBlank()) "" else at
            !edit.applyNote || note == existing.note -> existing.noteAt
            else -> at
        }
        val ops = reviseTagOps(existing?.tagOps.orEmpty(), existing?.tags.orEmpty(), tags, at)
        if (tags.isEmpty() && note.isBlank() && ops.isEmpty()) {
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
                tagOps = ops,
                noteAt = noteAt,
            )
        } else {
            attached[cueIndex] = existing.copy(
                index = cue.index,
                start = cue.start,
                end = cue.end,
                text = cue.text,
                tags = tags,
                note = note,
                tagOps = ops,
                noteAt = noteAt,
            )
        }
    }
    return alignment.copy(attached = attached)
}

fun clearTags(alignment: TagAlignment, cueIndices: Collection<Int>, at: String = tagNow()): TagAlignment {
    val attached = alignment.attached.toMutableMap()
    for (index in cueIndices) {
        val existing = attached[index] ?: continue
        val ops = reviseTagOps(existing.tagOps, existing.tags, emptyList(), at)
        if (existing.note.isBlank() && ops.isEmpty()) {
            attached.remove(index)
        } else {
            attached[index] = existing.copy(tags = emptyList(), tagOps = ops)
        }
    }
    return alignment.copy(attached = attached)
}

fun updateNote(alignment: TagAlignment, cueIndex: Int, note: String, at: String = tagNow()): TagAlignment {
    val existing = alignment.attached[cueIndex] ?: return alignment
    val noteAt = if (note == existing.note) existing.noteAt else at
    if (existing.tags.isEmpty() && note.isBlank() && existing.tagOps.isEmpty()) {
        return alignment.copy(attached = alignment.attached - cueIndex)
    }
    return alignment.copy(
        attached = alignment.attached + (cueIndex to existing.copy(note = note, noteAt = noteAt)),
    )
}

fun attachUnmatchedToCue(
    alignment: TagAlignment,
    entryId: String,
    cueIndex: Int,
    cue: SubtitleCue,
    at: String = tagNow(),
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
    val noteAt = when {
        existing == null -> incoming.noteAt
        existing.note.isNotBlank() -> existing.noteAt
        else -> incoming.noteAt.ifBlank { existing.noteAt }
    }
    val ops = reviseTagOps(
        (existing?.tagOps.orEmpty()) + incoming.tagOps,
        existing?.tags.orEmpty(),
        mergedTags,
        at,
    )
    if (mergedTags.isEmpty() && mergedNote.isBlank() && ops.isEmpty()) {
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
            tagOps = ops,
            noteAt = noteAt,
        )
    } else {
        existing.copy(
            tags = mergedTags,
            note = mergedNote,
            noteAt = noteAt,
            tagOps = ops,
            index = cue.index,
            start = cue.start,
            end = cue.end,
            text = cue.text,
        )
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
                tagOps = base.tagOps + entry.tagOps,
                noteAt = if (isNewerOrEqualUtc(entry.noteAt, base.noteAt) && entry.noteAt.isNotBlank()) {
                    entry.noteAt
                } else {
                    base.noteAt
                },
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

fun companionSubtitleNames(videoFileName: String, namesInDirectory: List<String>): List<String> {
    val videoStem = mediaStem(videoFileName)
    return namesInDirectory.filter { name ->
        isSubtitleFile(name) && subtitleOwnerStem(name, namesInDirectory) == videoStem
    }
}

fun subtitleOwnerStem(subtitleFileName: String, namesInDirectory: List<String>): String? {
    val subtitleStem = mediaStem(subtitleFileName)
    return namesInDirectory
        .filter { isMediaFile(it) }
        .map { mediaStem(it) }
        .distinct()
        .filter { stem -> subtitleStem == stem || subtitleStem.startsWith("${stem}_") }
        .maxByOrNull { it.length }
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
        parseJson(text) as? JsonValue.Obj
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
        append("      \"tag_ops\": [${encodeTagOps(entry.tagOps)}],\n")
        append("      \"note\": ${jsonString(entry.note)},\n")
        append("      \"note_at\": ${jsonString(entry.noteAt)}\n")
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
    val rawTags = when (val node = obj.map["tags"]) {
        is JsonValue.Arr -> node.items.mapNotNull { (it as? JsonValue.Str)?.value }
        null -> emptyList()
        else -> return null
    }
    val tagOps = when (val node = obj.map["tag_ops"]) {
        is JsonValue.Arr -> node.items.mapNotNull { parseTagOp(it) }
        null -> emptyList()
        else -> return null
    }
    val note = when (val node = obj.map["note"]) {
        is JsonValue.Str -> node.value
        null -> ""
        else -> return null
    }
    val noteAt = when (val node = obj.map["note_at"]) {
        is JsonValue.Str -> node.value
        null -> ""
        else -> return null
    }
    val tags = resolveVisibleTags(rawTags, tagOps)
    if (tags.isEmpty() && note.isBlank() && tagOps.isEmpty()) return null
    return TagEntry(id, index, start, end, text, tags, note, tagOps, noteAt)
}

private fun parseTagOp(node: JsonValue): TagOp? {
    val obj = node as? JsonValue.Obj ?: return null
    val name = (obj.map["name"] as? JsonValue.Str)?.value?.trim().orEmpty()
    if (name.isEmpty()) return null
    val present = (obj.map["present"] as? JsonValue.Bool)?.value ?: return null
    val at = (obj.map["at"] as? JsonValue.Str)?.value.orEmpty()
    return TagOp(name, present, at)
}

private fun encodeTagOps(ops: List<TagOp>): String =
    ops.joinToString(", ") { op ->
        "{\"name\":${jsonString(op.name)},\"present\":${if (op.present) "true" else "false"},\"at\":${jsonString(op.at)}}"
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

data class ExtractSourceFile(
    val label: String,
    val fileName: String,
    val rawText: String?,
)

data class ExtractWrite(
    val fileName: String,
    val kind: TagSyncKind,
    val document: TagDocument,
)

data class ExtractSkip(
    val label: String,
    val reason: String,
)

data class ExtractPlan(
    val foundAny: Boolean,
    val writes: List<ExtractWrite>,
    val skips: List<ExtractSkip>,
)

fun sameExtractFolder(sourceTreeId: String, destTreeId: String): Boolean =
    sourceTreeId.isNotBlank() && sourceTreeId == destTreeId

/** True when [destTreeId] sits inside the source tree and this file is under that destination. */
fun extractSourceInsideDestination(sourceDocumentId: String, sourceTreeId: String, destTreeId: String): Boolean {
    if (sourceTreeId.isBlank() || destTreeId.isBlank() || sourceTreeId == destTreeId) return false
    val destInsideSource = destTreeId == sourceTreeId || destTreeId.startsWith("$sourceTreeId/")
    if (!destInsideSource) return false
    return sourceDocumentId == destTreeId || sourceDocumentId.startsWith("$destTreeId/")
}

fun planTagExtract(
    sources: List<ExtractSourceFile>,
    existingDocuments: Map<String, TagDocument>,
    existingFileNames: Map<String, String> = emptyMap(),
    unreadableExistingKeys: Set<String> = emptySet(),
): ExtractPlan {
    if (sources.isEmpty()) {
        return ExtractPlan(foundAny = false, writes = emptyList(), skips = emptyList())
    }
    val skips = mutableListOf<ExtractSkip>()
    val groupOrder = mutableListOf<String>()
    val firstNames = linkedMapOf<String, String>()
    val valid = linkedMapOf<String, MutableList<TagDocument>>()
    for (source in sources) {
        val key = source.fileName.lowercase()
        if (key !in firstNames) {
            firstNames[key] = source.fileName
            groupOrder += key
            valid[key] = mutableListOf()
        }
        val (document, skip) = readExtractSource(source)
        if (skip != null) skips += skip else if (document != null) valid.getValue(key) += document
    }
    val writes = mutableListOf<ExtractWrite>()
    for (key in groupOrder) {
        val docs = valid.getValue(key)
        if (key in unreadableExistingKeys) {
            if (docs.isNotEmpty()) {
                val name = existingFileNames[key] ?: firstNames.getValue(key)
                skips += ExtractSkip(name, "保存位置里的同名标签无法读取")
            }
            continue
        }
        if (docs.isEmpty()) continue
        val outputName = existingFileNames[key] ?: firstNames.getValue(key)
        val subtitleName = subtitleFileNameFromTagFile(outputName) ?: continue
        val existing = existingDocuments[key]
        if (docs.size == 1 && existing == null) {
            writes += ExtractWrite(
                outputName,
                TagSyncKind.Copied,
                docs[0].copy(version = TAG_DOCUMENT_VERSION, subtitleFile = subtitleName),
            )
        } else {
            var combined = existing?.entries.orEmpty()
            for (doc in docs) combined = mergeTagEntries(combined, doc.entries)
            writes += ExtractWrite(
                outputName,
                TagSyncKind.Merged,
                TagDocument(TAG_DOCUMENT_VERSION, subtitleName, combined),
            )
        }
    }
    return ExtractPlan(foundAny = true, writes = writes, skips = skips)
}

fun describeExtractResults(foundAny: Boolean, writes: List<ExtractWrite>, skips: List<ExtractSkip>): String {
    if (!foundAny) return "来源文件夹里没有标签文件"
    val copied = writes.count { it.kind == TagSyncKind.Copied }
    val merged = writes.count { it.kind == TagSyncKind.Merged }
    val head = "直接复制 $copied 个\n合并 $merged 个\n跳过 ${skips.size} 个"
    if (skips.isEmpty()) return head
    return head + "\n\n" + skips.joinToString("\n") { "${it.label}（${it.reason}）" }
}

private fun readExtractSource(source: ExtractSourceFile): Pair<TagDocument?, ExtractSkip?> {
    if (source.rawText == null) return null to ExtractSkip(source.label, "无法读取")
    val subtitleName = subtitleFileNameFromTagFile(source.fileName)
        ?: return null to ExtractSkip(source.label, "标签文件无效")
    val parsed = parseTagDocument(source.rawText) ?: return null to ExtractSkip(source.label, "标签文件无效")
    if (parsed.subtitleFile != subtitleName) {
        return null to ExtractSkip(source.label, "字幕文件名与标签文件不一致")
    }
    if (parsed.entries.isEmpty()) return null to ExtractSkip(source.label, "没有有效内容")
    return parsed to null
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

