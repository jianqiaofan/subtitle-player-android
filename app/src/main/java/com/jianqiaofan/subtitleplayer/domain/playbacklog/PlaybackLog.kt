package com.jianqiaofan.subtitleplayer.domain.playbacklog

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

const val PLAYBACK_LOG_FILE = "playback.json"
const val PLAYBACK_MIN_MS = 1_000L
const val PLAYBACK_MAX_MS = 24L * 60L * 60L * 1000L
const val PLAYBACK_EARLIEST_MS = 1_577_836_800_000L
const val PLAYBACK_LATEST_MS = 4_102_444_800_000L
const val PLAYBACK_UPLOAD_LIMIT = 500

private val CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

data class PlaybackSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
)

data class PlaybackLog(
    val version: Int = 1,
    val sessions: List<PlaybackSession> = emptyList(),
)

fun newPlaybackSessionId(): String = UUID.randomUUID().toString().replace("-", "")

fun zoneOrShanghai(zone: ZoneId?): ZoneId {
    if (zone == null) return ZoneId.of("Asia/Shanghai")
    return try {
        CLOCK.format(Instant.EPOCH.atZone(zone))
        zone
    } catch (_: DateTimeException) {
        ZoneId.of("Asia/Shanghai")
    } catch (_: Exception) {
        ZoneId.of("Asia/Shanghai")
    }
}

fun deviceZone(): ZoneId = try {
    zoneOrShanghai(ZoneId.systemDefault())
} catch (_: Exception) {
    ZoneId.of("Asia/Shanghai")
}

fun formatDeviceTime(epochMs: Long, zone: ZoneId = deviceZone()): String {
    val safe = zoneOrShanghai(zone)
    return try {
        CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(safe))
    } catch (_: Exception) {
        CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai")))
    }
}

fun formatStudyDuration(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) / 1000L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 && minutes > 0 && seconds > 0 -> "${hours}小时${minutes}分${seconds}秒"
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
        hours > 0 && seconds > 0 -> "${hours}小时${seconds}秒"
        hours > 0 -> "${hours}小时"
        minutes > 0 && seconds > 0 -> "${minutes}分${seconds}秒"
        minutes > 0 -> "${minutes}分"
        else -> "${seconds}秒"
    }
}

fun studyTotalMillis(sessions: List<PlaybackSession>): Long =
    sessions.sumOf { session ->
        val end = session.endedAt ?: return@sumOf 0L
        (end - session.startedAt).coerceAtLeast(0L)
    }

fun formatSessionLine(session: PlaybackSession, zone: ZoneId = deviceZone()): String? {
    val end = session.endedAt ?: return null
    val span = (end - session.startedAt).coerceAtLeast(0L)
    return "${formatDeviceTime(session.startedAt, zone)}  →  ${formatDeviceTime(end, zone)}    ${formatStudyDuration(span)}"
}

fun isPlaybackId(id: String): Boolean =
    id.length in 16..64 && id.all { it in '0'..'9' || it in 'a'..'f' }

fun uploadableSession(session: PlaybackSession): Boolean {
    val end = session.endedAt ?: return false
    if (!isPlaybackId(session.id)) return false
    if (session.startedAt < PLAYBACK_EARLIEST_MS || session.startedAt >= PLAYBACK_LATEST_MS) return false
    if (end < PLAYBACK_EARLIEST_MS || end >= PLAYBACK_LATEST_MS) return false
    if (end <= session.startedAt) return false
    val span = end - session.startedAt
    return span in PLAYBACK_MIN_MS..PLAYBACK_MAX_MS
}

fun closeSession(log: PlaybackLog, id: String, endedAt: Long): PlaybackLog {
    val sessions = log.sessions.mapNotNull { session ->
        if (session.id != id) return@mapNotNull session
        if (endedAt - session.startedAt < PLAYBACK_MIN_MS) null
        else session.copy(endedAt = endedAt)
    }
    return log.copy(sessions = sessions)
}

fun beginSession(log: PlaybackLog, id: String, startedAt: Long): PlaybackLog {
    if (log.sessions.any { it.id == id }) return log
    return log.copy(sessions = listOf(PlaybackSession(id, startedAt)) + log.sessions)
}

/** Same id keeps the copy already stored. Remote-only ids are added. */
fun mergePlaybackSessions(local: List<PlaybackSession>, remote: List<PlaybackSession>): List<PlaybackSession> {
    val byId = linkedMapOf<String, PlaybackSession>()
    for (session in local) byId[session.id] = session
    for (session in remote) if (session.id !in byId) byId[session.id] = session
    return byId.values.sortedByDescending { it.startedAt }
}

fun sessionsForAccount(
    sessions: List<PlaybackSession>,
    owners: Map<String, String>,
    username: String,
): List<PlaybackSession> {
    if (username.isBlank()) return sessions
    return sessions.filter { session ->
        val owner = owners[session.id]
        owner.isNullOrBlank() || owner == username
    }
}

fun sessionsToUpload(
    sessions: List<PlaybackSession>,
    owners: Map<String, String>,
    username: String,
    openSessionId: String? = null,
): List<PlaybackSession> {
    if (username.isBlank()) return emptyList()
    return sessionsForAccount(sessions, owners, username)
        .filter { it.id != openSessionId && uploadableSession(it) }
        .sortedByDescending { it.startedAt }
}

fun encodePlaybackLog(log: PlaybackLog): String = buildString {
    append("{\n  \"version\": 1,\n  \"sessions\": [\n")
    log.sessions.forEachIndexed { index, session ->
        if (index > 0) append(",\n")
        append("    {\"id\": ${jsonString(session.id)}, \"started_at\": ${session.startedAt}")
        if (session.endedAt != null) append(", \"ended_at\": ${session.endedAt}")
        append("}")
    }
    append("\n  ]\n}\n")
}

fun parsePlaybackLog(raw: String): PlaybackLog {
    val root = try {
        parseJson(raw.removePrefix("\uFEFF").trim()) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return PlaybackLog()
    val sessions = (root.map["sessions"] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { node ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        val id = (item["id"] as? JsonValue.Str)?.value ?: return@mapNotNull null
        val started = (item["started_at"] as? JsonValue.Num)?.longOrNull() ?: return@mapNotNull null
        val ended = (item["ended_at"] as? JsonValue.Num)?.longOrNull()
        PlaybackSession(id, started, ended)
    }
    return PlaybackLog(version = 1, sessions = sessions)
}

fun encodePlaybackUpload(videoHash: String, videoStem: String, sessions: List<PlaybackSession>): String = buildString {
    val ready = sessions.filter { it.endedAt != null }
    append("{\"video_hash\":${jsonString(videoHash)},")
    append("\"video_stem\":${jsonString(videoStem)},")
    append("\"sessions\":[")
    ready.forEachIndexed { index, session ->
        if (index > 0) append(',')
        append("{\"id\":${jsonString(session.id)},\"started_at\":${session.startedAt},\"ended_at\":${session.endedAt}}")
    }
    append("]}")
}

fun parsePlaybackSnapshot(body: String): List<PlaybackSession>? {
    val root = try {
        parseJson(body) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    return (root.map["sessions"] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { node ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        val id = (item["id"] as? JsonValue.Str)?.value ?: return@mapNotNull null
        val started = (item["started_at"] as? JsonValue.Num)?.longOrNull() ?: return@mapNotNull null
        val ended = (item["ended_at"] as? JsonValue.Num)?.longOrNull() ?: return@mapNotNull null
        PlaybackSession(id, started, ended)
    }
}
