package com.jianqiaofan.subtitleplayer.domain.subtitle

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleFormat
import com.jianqiaofan.subtitleplayer.domain.time.secondsToTimeFields
import kotlin.math.round

fun loadSubtitleContent(raw: String, format: SubtitleFormat): List<SubtitleCue> {
    val content = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    return when (format) {
        SubtitleFormat.Srt -> loadSrt(content)
        SubtitleFormat.Vtt -> loadVtt(content)
    }
}

fun cuesToFileContent(cues: List<SubtitleCue>, format: SubtitleFormat): String =
    when (format) {
        SubtitleFormat.Srt -> cuesToSrt(cues)
        SubtitleFormat.Vtt -> cuesToVtt(cues)
    }

fun formatTimestamp(seconds: Double, vtt: Boolean = false): String {
    val millisTotal = round(seconds * 1000.0 + 1e-9).toLong().coerceAtLeast(0L)
    val hours = millisTotal / 3_600_000L
    val rem = millisTotal % 3_600_000L
    val minutes = rem / 60_000L
    val rem2 = rem % 60_000L
    val secs = rem2 / 1_000L
    val ms = rem2 % 1_000L
    val sep = if (vtt) '.' else ','
    return "%02d:%02d:%02d%c%03d".format(hours, minutes, secs, sep, ms)
}

fun formatClock(seconds: Double): String {
    val fields = secondsToTimeFields(seconds)
    return if (fields.hours > 0) {
        "%d:%02d:%02d".format(fields.hours, fields.minutes, fields.seconds)
    } else {
        "%02d:%02d".format(fields.minutes, fields.seconds)
    }
}

fun formatCueListHeader(cue: SubtitleCue): String =
    "${cue.index}. [${formatClock(cue.start)} → ${formatClock(cue.end)}]"

fun formatCueListLine(cue: SubtitleCue): String {
    val text = cue.text.replace("\n", " / ")
    return "${formatCueListHeader(cue)} $text"
}

fun findCueIndexAtTime(cues: List<SubtitleCue>, seconds: Double): Int {
    if (cues.isEmpty()) return -1
    cues.forEachIndexed { index, cue ->
        if (cue.start <= seconds && seconds <= cue.end) return index
    }
    cues.forEachIndexed { index, cue ->
        if (cue.start > seconds) return (index - 1).coerceAtLeast(0)
    }
    return cues.lastIndex
}

fun parseTimestamp(value: String): Double = tryParseTimestamp(value) ?: 0.0

fun tryParseTimestamp(value: String): Double? {
    val token = value.trim().split(Regex("\\s+")).firstOrNull()?.replace(',', '.') ?: return null
    if (token.isEmpty()) return null
    val parts = token.split(':')
    val hours: Int
    val minutesPart: String
    val secondsPart: String
    when (parts.size) {
        2 -> {
            hours = 0
            minutesPart = parts[0]
            secondsPart = parts[1]
        }
        3 -> {
            hours = parts[0].toIntOrNull() ?: return null
            minutesPart = parts[1]
            secondsPart = parts[2]
        }
        else -> return null
    }
    val minutes = minutesPart.toIntOrNull() ?: return null
    val secBits = secondsPart.split('.', limit = 2)
    val seconds = secBits[0].toIntOrNull() ?: return null
    val millis = if (secBits.size > 1) fractionToMillis(secBits[1]) else 0
    if (minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
    if (hours < 0) return null
    return hours * 3600.0 + minutes * 60.0 + seconds + millis / 1000.0
}

private fun fractionToMillis(fraction: String): Int {
    val digits = fraction.filter { it.isDigit() }
    if (digits.isEmpty()) return 0
    return if (digits.length <= 3) {
        digits.padEnd(3, '0').toInt()
    } else {
        digits.take(3).toInt()
    }
}

private fun loadSrt(content: String): List<SubtitleCue> {
    val blocks = content.trim().split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    val cues = mutableListOf<SubtitleCue>()
    for (block in blocks) {
        val lines = block.split('\n')
        if (lines.size < 3) continue
        val index = lines[0].trim().toIntOrNull() ?: continue
        val timing = lines[1]
        if ("-->" !in timing) continue
        val parts = timing.split("-->")
        if (parts.size < 2) continue
        val text = lines.drop(2).joinToString("\n").trim()
        cues += SubtitleCue(
            index = index,
            start = parseTimestamp(parts[0]),
            end = parseTimestamp(parts[1]),
            text = text,
        )
    }
    return cues
}

private fun loadVtt(content: String): List<SubtitleCue> {
    val lines = content.split('\n')
    val blocks = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    for (line in lines) {
        val stripped = line.trim()
        if (stripped.equals("WEBVTT", ignoreCase = true) || stripped.startsWith("NOTE")) {
            continue
        }
        if (stripped.isEmpty()) {
            if (current.isNotEmpty()) {
                blocks += current.toList()
                current.clear()
            }
            continue
        }
        current += line
    }
    if (current.isNotEmpty()) blocks += current.toList()

    val cues = mutableListOf<SubtitleCue>()
    blocks.forEachIndexed { i, block ->
        if (block.size < 2 || "-->" !in block[0]) return@forEachIndexed
        val parts = block[0].split("-->")
        if (parts.size < 2) return@forEachIndexed
        val text = block.drop(1).joinToString("\n").trim()
        cues += SubtitleCue(
            index = i + 1,
            start = parseTimestamp(parts[0]),
            end = parseTimestamp(parts[1]),
            text = text,
        )
    }
    return cues
}

private fun cuesToSrt(cues: List<SubtitleCue>): String {
    val lines = mutableListOf<String>()
    for (cue in cues) {
        lines += cue.index.toString()
        lines += "${formatTimestamp(cue.start)} --> ${formatTimestamp(cue.end)}"
        lines += cue.text.trim()
        lines += ""
    }
    return lines.joinToString("\n").trim() + "\n"
}

private fun cuesToVtt(cues: List<SubtitleCue>): String {
    val lines = mutableListOf("WEBVTT", "")
    for (cue in cues) {
        lines += "${formatTimestamp(cue.start, vtt = true)} --> ${formatTimestamp(cue.end, vtt = true)}"
        lines += cue.text.trim()
        lines += ""
    }
    return lines.joinToString("\n").trim() + "\n"
}

fun effectiveRepeatEnd(start: Double, end: Double, durationSec: Double): Double {
    var finish = end
    if (finish <= start) finish = start + 0.5
    if (durationSec > 0) finish = minOf(finish, durationSec)
    return finish
}

val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
