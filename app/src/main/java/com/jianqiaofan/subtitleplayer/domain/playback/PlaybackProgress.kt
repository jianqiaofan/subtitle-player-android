package com.jianqiaofan.subtitleplayer.domain.playback

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

data class PlaybackRecord(
    val positionMs: Long,
    val durationMs: Long = 0L,
    val leftAt: Long = 0L,
)

fun playedPercent(positionMs: Long, durationMs: Long): Int? {
    if (durationMs <= 0L || positionMs < 0L) return null
    val clamped = positionMs.coerceAtMost(durationMs)
    return ((clamped * 100.0) / durationMs).roundToInt().coerceIn(0, 100)
}

fun resolvePlayedPercent(record: PlaybackRecord, mediaDurationMs: Long?): Int? {
    val duration = record.durationMs.takeIf { it > 0L } ?: mediaDurationMs?.takeIf { it > 0L } ?: return null
    return playedPercent(record.positionMs, duration)
}

fun formatLastWatched(
    leftAt: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val left = Instant.ofEpochMilli(leftAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date = left.toLocalDate()
    val clock = "%02d:%02d".format(left.hour, left.minute)
    return when {
        date == today -> "今天 $clock"
        date == today.minusDays(1) -> "昨天 $clock"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 $clock"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日 $clock"
    }
}

fun watchProgressLabel(
    record: PlaybackRecord?,
    mediaDurationMs: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (record == null) return null
    val time = record.leftAt.takeIf { it > 0L }?.let { formatLastWatched(it, now, zone) }
    val percent = resolvePlayedPercent(record, mediaDurationMs)?.let { "已看 $it%" }
    val line = listOfNotNull(time, percent).joinToString(" · ")
    return line.ifBlank { null }
}

fun encodePlayback(records: Map<String, PlaybackRecord>): String =
    records.entries.joinToString("\n") { (uri, record) ->
        "$uri\t${record.positionMs}\t${record.durationMs}\t${record.leftAt}"
    }

fun decodePlayback(raw: String): Map<String, PlaybackRecord> =
    raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val position = parts[1].toLongOrNull() ?: return@mapNotNull null
            val duration = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val leftAt = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            parts[0] to PlaybackRecord(position, duration, leftAt)
        }
        .toMap()
