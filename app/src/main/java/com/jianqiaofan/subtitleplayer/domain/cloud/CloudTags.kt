package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.jsonNumber
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION
import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.TagOp
import com.jianqiaofan.subtitleplayer.domain.tags.latestTagOps
import com.jianqiaofan.subtitleplayer.domain.tags.newTagId
import com.jianqiaofan.subtitleplayer.domain.tags.resolveVisibleTags
import com.jianqiaofan.subtitleplayer.domain.tags.stripWhitespace
import com.jianqiaofan.subtitleplayer.domain.tags.visibleTagNames
import com.jianqiaofan.subtitleplayer.domain.time.sameUtcInstant
import com.jianqiaofan.subtitleplayer.domain.time.utcMillis

data class CloudTagEntry(
    val id: String,
    val index: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val tagOps: List<TagOp>,
    val note: String,
    val noteAt: String,
)

data class CloudTagDocument(
    val subtitleFile: String,
    val entries: List<CloudTagEntry>,
)

data class TagUploadPlan(
    val upload: CloudTagDocument?,
    val localEntries: List<TagEntry>,
    val localChanged: Boolean,
)

private data class SentencePairing(
    val cloudByLocal: List<CloudTagEntry?>,
    val unmatchedCloud: List<CloudTagEntry>,
)

fun planTagUpload(local: TagDocument, baseline: CloudTagDocument?, now: String): TagUploadPlan {
    var changed = false
    val prepared = local.entries.map { entry ->
        var ops = entry.tagOps
        var noteAt = entry.noteAt
        val visible = resolveVisibleTags(entry.tags, ops)
        val latest = latestTagOps(ops)
        for (name in visible) {
            val current = latest[name]
            if (current == null || !current.present) {
                ops = ops + TagOp(name, true, now)
            }
        }
        if (entry.note.isNotBlank() && noteAt.isBlank()) noteAt = now
        val updated = entry.copy(tags = resolveVisibleTags(entry.tags, ops), tagOps = ops, noteAt = noteAt)
        if (updated != entry) changed = true
        updated
    }.toMutableList()

    val pairing = pairSentences(prepared, baseline?.entries.orEmpty())
    val uploadEntries = mutableListOf<CloudTagEntry>()
    for (index in prepared.indices) {
        val base = pairing.cloudByLocal[index]
        var stored = prepared[index]
        val baseLatest = latestTagOps(base?.tagOps.orEmpty())
        var ops = stored.tagOps
        val names = (latestTagOps(ops).keys + baseLatest.keys).distinct()
        for (name in names) {
            if (latestTagOps(ops)[name] == null && baseLatest[name]?.present == true) {
                ops = ops + TagOp(name, false, now)
            }
        }
        if (ops != stored.tagOps) {
            stored = stored.copy(tags = resolveVisibleTags(stored.tags, ops), tagOps = ops)
            prepared[index] = stored
            changed = true
        }
        if (base == null) {
            val presentOps = latestTagOps(stored.tagOps).values.filter { it.present }
            if (presentOps.isEmpty() && stored.note.isBlank()) continue
            uploadEntries += stored.toCloud(presentOps)
            continue
        }
        val changedOps = latestTagOps(stored.tagOps).values.filter { op ->
            val previous = baseLatest[op.name]
            previous == null || !sameTagOp(op, previous)
        }
        val noteChanged = stored.note != base.note || !sameUtcInstant(stored.noteAt, base.noteAt)
        if (changedOps.isEmpty() && !noteChanged) continue
        uploadEntries += stored.toCloud(changedOps)
    }
    for (cloud in pairing.unmatchedCloud) {
        val deletions = latestTagOps(cloud.tagOps).filterValues { it.present }.map { TagOp(it.key, false, now) }
        if (deletions.isEmpty() && cloud.note.isBlank()) continue
        uploadEntries += cloud.copy(
            tagOps = deletions,
            note = "",
            noteAt = if (cloud.note.isNotBlank()) now else cloud.noteAt,
        )
    }
    val upload = if (uploadEntries.isEmpty()) null else CloudTagDocument(local.subtitleFile, uploadEntries)
    return TagUploadPlan(upload, prepared, changed)
}

fun mergeCloudTags(local: TagDocument?, cloud: CloudTagDocument, localSubtitleFile: String): TagDocument {
    val localEntries = local?.entries.orEmpty()
    val pairing = pairSentences(localEntries, cloud.entries)
    val merged = localEntries.mapIndexed { index, entry ->
        val remote = pairing.cloudByLocal[index]
        if (remote == null) entry else mergeTagEntry(entry, remote)
    }.toMutableList()
    for (remote in pairing.unmatchedCloud) {
        val tags = resolveVisibleTags(emptyList(), remote.tagOps)
        if (tags.isEmpty() && remote.note.isBlank() && remote.tagOps.isEmpty()) continue
        merged += TagEntry(
            id = remote.id.ifBlank { newTagId() },
            index = remote.index,
            start = remote.start,
            end = remote.end,
            text = remote.text,
            tags = tags,
            note = remote.note,
            tagOps = remote.tagOps,
            noteAt = remote.noteAt,
        )
    }
    return TagDocument(
        TAG_DOCUMENT_VERSION,
        localSubtitleFile,
        merged.filter { it.tags.isNotEmpty() || it.note.isNotBlank() || it.tagOps.isNotEmpty() },
    )
}

fun describeCloudTagDocument(document: CloudTagDocument): String {
    if (document.entries.isEmpty()) return "没有标签条目"
    return document.entries.joinToString("\n\n") { entry ->
        val tags = visibleTagNames(entry.tagOps)
        val tagText = if (tags.isEmpty()) "（无标签）" else tags.joinToString("、")
        val note = if (entry.note.isBlank()) "" else "\n备注：${entry.note}"
        "${entry.index}. ${entry.text}\n标签：$tagText$note"
    }
}

fun encodeCloudTagDocument(document: CloudTagDocument): String = buildString {
    append("{\"version\":2,\"subtitle_file\":${jsonString(document.subtitleFile)},\"entries\":[")
    document.entries.forEachIndexed { index, entry ->
        if (index > 0) append(',')
        append("{\"id\":${jsonString(entry.id)},\"index\":${entry.index},")
        append("\"start\":${jsonNumber(entry.start)},\"end\":${jsonNumber(entry.end)},")
        append("\"text\":${jsonString(entry.text)},\"tag_ops\":[")
        entry.tagOps.forEachIndexed { opIndex, op ->
            if (opIndex > 0) append(',')
            append("{\"name\":${jsonString(op.name)},\"present\":${if (op.present) "true" else "false"},\"at\":${jsonString(op.at)}}")
        }
        append("],\"note\":${jsonString(entry.note)},\"note_at\":${jsonString(entry.noteAt)}}")
    }
    append("]}")
}

fun parseCloudTagDocument(raw: String): CloudTagDocument? {
    val root = try {
        parseJson(raw.removePrefix("\uFEFF").trim()) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    return cloudTagDocument(root.map)
}

fun cloudTagDocument(map: Map<String, JsonValue>): CloudTagDocument? {
    val subtitleFile = (map["subtitle_file"] as? JsonValue.Str)?.value ?: return null
    val entries = (map["entries"] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { node ->
        cloudTagEntry((node as? JsonValue.Obj)?.map ?: return@mapNotNull null)
    }
    return CloudTagDocument(subtitleFile, entries)
}

private fun mergeTagEntry(local: TagEntry, cloud: CloudTagEntry): TagEntry {
    val ops = mergeOpLists(local.tagOps, cloud.tagOps)
    val (note, noteAt) = preferNote(local.note, local.noteAt, cloud.note, cloud.noteAt)
    return local.copy(
        tags = resolveVisibleTags(local.tags, ops),
        note = note,
        noteAt = noteAt,
        tagOps = ops,
    )
}

private fun preferNote(localNote: String, localAt: String, cloudNote: String, cloudAt: String): Pair<String, String> {
    val localMs = utcMillis(localAt)
    val cloudMs = utcMillis(cloudAt)
    return when {
        localMs != null && cloudMs != null -> if (cloudMs > localMs) cloudNote to cloudAt else localNote to localAt
        localMs != null -> localNote to localAt
        cloudMs != null && localNote.isNotBlank() && localNote != cloudNote -> localNote to localAt
        cloudMs != null -> cloudNote to cloudAt
        localNote.isNotBlank() -> localNote to localAt
        else -> cloudNote to cloudAt
    }
}

private fun mergeOpLists(local: List<TagOp>, cloud: List<TagOp>): List<TagOp> {
    val seen = linkedSetOf<String>()
    val out = mutableListOf<TagOp>()
    for (op in local + cloud) {
        val key = op.name + "\u0000" + op.present + "\u0000" + op.at
        if (seen.add(key)) out += op
    }
    return out
}

private fun pairSentences(local: List<TagEntry>, cloud: List<CloudTagEntry>): SentencePairing {
    val used = BooleanArray(cloud.size)
    val match = arrayOfNulls<CloudTagEntry>(local.size)
    fun claim(localIndex: Int, cloudIndex: Int) {
        if (match[localIndex] != null || used[cloudIndex]) return
        used[cloudIndex] = true
        match[localIndex] = cloud[cloudIndex]
    }
    for (i in local.indices) {
        val id = local[i].id
        if (id.isBlank()) continue
        val j = cloud.indices.firstOrNull { !used[it] && cloud[it].id == id }
        if (j != null) claim(i, j)
    }
    for (i in local.indices) {
        if (match[i] != null) continue
        val text = stripWhitespace(local[i].text)
        if (text.isEmpty()) continue
        val j = cloud.indices.firstOrNull {
            !used[it] && cloud[it].index == local[i].index && stripWhitespace(cloud[it].text) == text
        }
        if (j != null) claim(i, j)
    }
    for (i in local.indices) {
        if (match[i] != null) continue
        val text = stripWhitespace(local[i].text)
        if (text.isEmpty()) continue
        val localHits = local.indices.filter { match[it] == null && stripWhitespace(local[it].text) == text }
        val cloudHits = cloud.indices.filter { !used[it] && stripWhitespace(cloud[it].text) == text }
        if (localHits.size == 1 && cloudHits.size == 1 && localHits[0] == i) claim(i, cloudHits[0])
    }
    return SentencePairing(match.toList(), cloud.indices.filter { !used[it] }.map { cloud[it] })
}

private fun sameTagOp(left: TagOp, right: TagOp): Boolean =
    left.name == right.name && left.present == right.present && sameUtcInstant(left.at, right.at)

private fun TagEntry.toCloud(ops: List<TagOp>): CloudTagEntry =
    CloudTagEntry(id, index, start, end, text, ops, note, noteAt)

private fun cloudTagEntry(map: Map<String, JsonValue>): CloudTagEntry? {
    val index = (map["index"] as? JsonValue.Num)?.value?.toInt() ?: return null
    val start = (map["start"] as? JsonValue.Num)?.value ?: return null
    val end = (map["end"] as? JsonValue.Num)?.value ?: return null
    val text = (map["text"] as? JsonValue.Str)?.value ?: return null
    val id = (map["id"] as? JsonValue.Str)?.value.orEmpty()
    val ops = (map["tag_ops"] as? JsonValue.Arr)?.items?.mapNotNull { node ->
        val obj = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        val name = (obj["name"] as? JsonValue.Str)?.value?.trim().orEmpty()
        val present = (obj["present"] as? JsonValue.Bool)?.value ?: return@mapNotNull null
        if (name.isEmpty()) return@mapNotNull null
        TagOp(name, present, (obj["at"] as? JsonValue.Str)?.value.orEmpty())
    }.orEmpty()
    val note = (map["note"] as? JsonValue.Str)?.value.orEmpty()
    val noteAt = (map["note_at"] as? JsonValue.Str)?.value.orEmpty()
    return CloudTagEntry(id, index, start, end, text, ops, note, noteAt)
}
