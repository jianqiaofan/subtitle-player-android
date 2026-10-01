package com.jianqiaofan.subtitleplayer.domain.timeline

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.orderedTagNames
import com.jianqiaofan.subtitleplayer.domain.tags.resolveVisibleTags
import com.jianqiaofan.subtitleplayer.domain.tags.reviseTagOps
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor
import kotlin.math.min

const val TIMELINE_SUFFIX = "_时间线.srt"

val TIMELINE_STEP_SECONDS = listOf(5, 10, 15, 30, 60, 120, 300)

fun timelineSubtitleFileName(videoStem: String): String = videoStem + TIMELINE_SUFFIX

fun timelineTagFileName(videoStem: String): String = tagFileNameFor(timelineSubtitleFileName(videoStem))

fun isTimelineSubtitleFile(videoFileName: String, subtitleFileName: String): Boolean =
    subtitleFileName == timelineSubtitleFileName(mediaStem(videoFileName))

fun defaultTimelineStepSeconds(durationSec: Double): Int = when {
    durationSec <= 60.0 -> 10
    durationSec <= 600.0 -> 30
    else -> 60
}

fun timelineStepLabel(seconds: Int): String = when {
    seconds % 60 == 0 && seconds >= 60 -> "${seconds / 60}分钟"
    else -> "${seconds}秒"
}

fun buildTimeline(durationSec: Double, stepSec: Int): List<SubtitleCue> {
    if (durationSec <= 0.0 || stepSec <= 0) return emptyList()
    val cues = mutableListOf<SubtitleCue>()
    var start = 0.0
    var index = 1
    while (start < durationSec - 0.000_001 && index < 100_000) {
        val end = min(start + stepSec, durationSec)
        if (end <= start) break
        cues += SubtitleCue(index, start, end, "")
        start = end
        index += 1
    }
    return cues
}

fun cueIndexContaining(cues: List<SubtitleCue>, startSec: Double): Int? {
    if (cues.isEmpty()) return null
    val last = cues.lastIndex
    return cues.indices.firstOrNull { index ->
        val cue = cues[index]
        if (index == last) startSec >= cue.start && startSec <= cue.end + 1e-6
        else startSec >= cue.start && startSec < cue.end
    }
}

data class RehomeResult(
    val subtitleEntries: List<TagEntry>,
    val timelineEntries: List<TagEntry>,
    val movedIds: Set<String>,
)

/**
 * Timeline tags hang on the real cue that contains their start.
 * The original id is kept. An id already on the subtitle is not attached again.
 * Moved tags are cancelled on the timeline document so the next tag sync uploads the removal.
 */
fun rehomeTimelineTags(
    timelineEntries: List<TagEntry>,
    cues: List<SubtitleCue>,
    subtitleEntries: List<TagEntry>,
    now: String,
): RehomeResult {
    val subtitle = subtitleEntries.toMutableList()
    val ids = subtitle.map { it.id }.toMutableSet()
    val moved = mutableSetOf<String>()
    for (entry in timelineEntries) {
        val visible = resolveVisibleTags(entry.tags, entry.tagOps)
        if (visible.isEmpty() && entry.note.isBlank()) continue
        val cueIndex = cueIndexContaining(cues, entry.start) ?: continue
        val cue = cues[cueIndex]
        if (entry.id in ids) {
            moved += entry.id
            continue
        }
        val occupant = subtitle.indexOfFirst { it.index == cue.index || (it.start >= cue.start && it.start <= cue.end) }
        if (occupant >= 0) {
            subtitle[occupant] = unionTagEntry(subtitle[occupant], entry, now)
            moved += entry.id
        } else {
            val attached = entry.copy(
                index = cue.index,
                start = cue.start,
                end = cue.end,
                text = cue.text,
                tags = orderedTagNames(visible),
            )
            subtitle += attached
            ids += attached.id
            moved += entry.id
        }
    }
    val timeline = timelineEntries.map { entry ->
        if (entry.id in moved) cancelTagEntry(entry, now) else entry
    }
    return RehomeResult(subtitle, timeline, moved)
}

fun cancelTagEntry(entry: TagEntry, now: String): TagEntry {
    val visible = resolveVisibleTags(entry.tags, entry.tagOps)
    val ops = reviseTagOps(entry.tagOps, visible, emptyList(), now)
    return entry.copy(tags = emptyList(), tagOps = ops)
}

private fun unionTagEntry(target: TagEntry, incoming: TagEntry, now: String): TagEntry {
    val incomingTags = resolveVisibleTags(incoming.tags, incoming.tagOps)
    val after = orderedTagNames(target.tags + incomingTags)
    val ops = reviseTagOps(target.tagOps, target.tags, after, now)
    val note = target.note.ifBlank { incoming.note }
    val noteAt = if (target.note.isBlank() && incoming.note.isNotBlank()) incoming.noteAt else target.noteAt
    return target.copy(tags = after, tagOps = ops, note = note, noteAt = noteAt)
}

fun alignTimelineTags(cues: List<SubtitleCue>, entries: List<TagEntry>): Pair<Map<Int, TagEntry>, List<TagEntry>> {
    val attached = linkedMapOf<Int, TagEntry>()
    val hidden = mutableListOf<TagEntry>()
    for (entry in entries) {
        val visible = resolveVisibleTags(entry.tags, entry.tagOps)
        if (visible.isEmpty() && entry.note.isBlank()) {
            if (entry.tagOps.isNotEmpty()) hidden += entry
            continue
        }
        val cueIndex = cueIndexContaining(cues, entry.start)
        if (cueIndex == null || cueIndex in attached) {
            hidden += entry
            continue
        }
        val cue = cues[cueIndex]
        attached[cueIndex] = entry.copy(
            index = cue.index,
            start = cue.start,
            end = cue.end,
            tags = orderedTagNames(visible),
        )
    }
    return attached to hidden
}

fun timelineEntriesForSave(visible: List<TagEntry>, hidden: List<TagEntry>): List<TagEntry> {
    val ids = visible.map { it.id }.toSet()
    return visible + hidden.filter { it.id !in ids && it.tagOps.isNotEmpty() }
}
