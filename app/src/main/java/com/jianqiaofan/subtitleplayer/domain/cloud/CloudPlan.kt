package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.time.formatUtcLocal
import java.time.ZoneId

const val CLOUD_BODY_LIMIT_BYTES = 8 * 1024 * 1024

object CloudMessages {
    const val OPEN_VIDEO_FIRST = "请先用播放器打开对应视频，配套文件夹里会生成该文件。"
    const val BAD_HASH_NAME = "哈希文件名不是「视频全名.videohash.json」。"
    const val NAME_MISMATCH = "字幕名和视频主文件名对不上。字幕名必须与主文件名相同，或只在后面加「_语言」。"
    const val BAD_HASH = "文件里的 hash 不是 64 位小写十六进制。"
    const val AMBIGUOUS = "同时对上多个不同的哈希，无法确定是哪一部视频。"
    const val NOT_SUBTITLE = "只支持 .srt 和 .vtt 字幕。"
    const val FOLDER_UNREADABLE = "无法读取字幕所在文件夹。请先在媒体库授权该文件夹。"
}

data class NamedText(val fileName: String, val text: String)

sealed class HashResolve {
    data class Ok(val videoFileName: String, val videoStem: String, val hash: String) : HashResolve()
    data class Reject(val reason: String) : HashResolve()
}

fun subtitleSuffix(videoStem: String, subtitleFileName: String): String? {
    if (!isSubtitleFile(subtitleFileName)) return null
    val stem = mediaStem(subtitleFileName)
    return when {
        stem == videoStem -> subtitleFileName.substring(stem.length)
        stem.startsWith("${videoStem}_") -> subtitleFileName.substring(videoStem.length)
        else -> null
    }
}

fun localSubtitleFileName(videoStem: String, suffix: String): String = videoStem + suffix

fun subtitleContentsMatch(local: String, remoteContent: String, remoteHash: String): Boolean {
    val normalizedLocal = local.removePrefix("\uFEFF")
    val normalizedRemote = remoteContent.removePrefix("\uFEFF")
    if (normalizedLocal == normalizedRemote) return true
    return sha256Hex(normalizedLocal) == remoteHash.lowercase()
}

data class LocalSubtitleContent(val fileName: String, val content: String)

data class SubtitleOffer(
    val suffix: String,
    val fileName: String,
    val content: String,
    val contentHash: String,
    val updatedAt: String,
)

fun subtitleDownloadOffers(
    videoStem: String,
    remote: List<RemoteSubtitle>,
    local: List<LocalSubtitleContent>,
): List<SubtitleOffer> {
    return remote.mapNotNull { item ->
        if (item.suffix.isBlank()) return@mapNotNull null
        val existing = local.find { subtitleSuffix(videoStem, it.fileName) == item.suffix }
        if (existing != null && subtitleContentsMatch(existing.content, item.content, item.contentHash)) {
            return@mapNotNull null
        }
        SubtitleOffer(
            suffix = item.suffix,
            fileName = localSubtitleFileName(videoStem, item.suffix),
            content = item.content,
            contentHash = item.contentHash,
            updatedAt = item.updatedAt,
        )
    }
}

fun shouldOfferTagMerge(
    remoteHash: String,
    baselineHash: String?,
    localExists: Boolean,
    localHasVisibleTags: Boolean,
): Boolean {
    val cloudChanged = baselineHash.isNullOrBlank() || baselineHash != remoteHash
    if (!cloudChanged && localExists && localHasVisibleTags) return false
    return remoteHash.isNotBlank()
}

fun mayOfferShares(hasValidLocalSubtitle: Boolean, downloadedOwn: Boolean): Boolean =
    !hasValidLocalSubtitle && !downloadedOwn

fun cloudTimeLabel(updatedAt: String, zone: ZoneId = ZoneId.systemDefault()): String =
    formatUtcLocal(updatedAt, zone)

/**
 * Hash files are every `*.videohash.json` in the subtitle's folder.
 * Longer video stems claim a subtitle before shorter ones.
 */
fun resolveSubtitleHash(subtitleFileName: String, hashFiles: List<NamedText>): HashResolve {
    if (!isSubtitleFile(subtitleFileName)) return HashResolve.Reject(CloudMessages.NOT_SUBTITLE)
    if (hashFiles.isEmpty()) return HashResolve.Reject(CloudMessages.OPEN_VIDEO_FIRST)
    val parsed = hashFiles.map { file ->
        val videoName = videoFileNameFromHashFile(file.fileName)
        val record = videoName?.let { parseVideoHash(file.text) }
        Triple(videoName, record, file.fileName)
    }
    val valid = parsed.mapNotNull { (videoName, record, _) ->
        if (videoName == null) null else Triple(videoName, mediaStem(videoName), record)
    }
    if (valid.isEmpty()) return HashResolve.Reject(CloudMessages.BAD_HASH_NAME)
    val matching = valid.filter { (_, stem, _) ->
        val subtitleStem = mediaStem(subtitleFileName)
        subtitleStem == stem || subtitleStem.startsWith("${stem}_")
    }
    if (matching.isEmpty()) return HashResolve.Reject(CloudMessages.NAME_MISMATCH)
    val longest = matching.maxOf { it.second.length }
    val best = matching.filter { it.second.length == longest }
    val hashes = best.mapNotNull { (_, _, record) ->
        record?.hash?.takeIf { isSha256Hex(it) }
    }.distinct()
    val unreadable = best.any { triple ->
        val record = triple.third
        record == null || !isSha256Hex(record.hash)
    }
    if (best.size > 1 && (hashes.size != 1 || unreadable)) {
        return HashResolve.Reject(if (hashes.isEmpty()) CloudMessages.BAD_HASH else CloudMessages.AMBIGUOUS)
    }
    if (hashes.size > 1) return HashResolve.Reject(CloudMessages.AMBIGUOUS)
    if (hashes.size != 1 || unreadable) return HashResolve.Reject(CloudMessages.BAD_HASH)
    val chosen = best.first { isSha256Hex(it.third?.hash.orEmpty()) }
    return HashResolve.Ok(chosen.first, chosen.second, hashes.single())
}
