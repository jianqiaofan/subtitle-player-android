package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import java.security.MessageDigest

const val VIDEO_HASH_SUFFIX = ".videohash.json"

data class VideoHashRecord(
    val hash: String,
    val size: Long,
    val mtimeNs: Long,
)

fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

fun videoHashFileName(videoFileName: String): String = videoFileName + VIDEO_HASH_SUFFIX

fun videoFileNameFromHashFile(name: String): String? {
    if (!name.endsWith(VIDEO_HASH_SUFFIX)) return null
    val video = name.removeSuffix(VIDEO_HASH_SUFFIX)
    return video.takeIf { isMediaFile(it) }
}

fun parseVideoHash(raw: String): VideoHashRecord? {
    val root = try {
        parseJson(raw.removePrefix("\uFEFF").trim()) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    val hash = (root.map["hash"] as? JsonValue.Str)?.value ?: return null
    val size = (root.map["size"] as? JsonValue.Num)?.longOrNull() ?: return null
    val mtimeNs = (root.map["mtime_ns"] as? JsonValue.Num)?.longOrNull() ?: return null
    return VideoHashRecord(hash, size, mtimeNs)
}

fun encodeVideoHash(record: VideoHashRecord): String = buildString {
    append("{\n")
    append("  \"hash\": ${jsonString(record.hash)},\n")
    append("  \"size\": ${record.size},\n")
    append("  \"mtime_ns\": ${record.mtimeNs}\n")
    append("}\n")
}

/** Accepts a desktop nanosecond mtime or a millisecond mtime for the same instant. */
fun hashCacheValid(record: VideoHashRecord, size: Long, modifiedMs: Long): Boolean {
    if (!isSha256Hex(record.hash) || record.size != size) return false
    val storedMs = if (record.mtimeNs >= 1_000_000_000_000_000L) {
        record.mtimeNs / 1_000_000L
    } else {
        record.mtimeNs
    }
    return storedMs == modifiedMs
}
