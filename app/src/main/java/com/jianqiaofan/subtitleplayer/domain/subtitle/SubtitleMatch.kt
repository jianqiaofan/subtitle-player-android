package com.jianqiaofan.subtitleplayer.domain.subtitle

import com.jianqiaofan.subtitleplayer.domain.model.SubtitleTrack
import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem

data class NamedSubtitleFile(
    val fileName: String,
    val documentUri: String,
)

fun subtitleDisplayName(mediaStem: String, subtitleFileName: String): String? {
    val stem = mediaStem(subtitleFileName)
    return when {
        stem == mediaStem -> "默认"
        stem.startsWith("${mediaStem}_") -> stem.removePrefix("${mediaStem}_")
        else -> null
    }
}

fun findSubtitlesForMedia(
    mediaFileName: String,
    folderFiles: List<NamedSubtitleFile>,
): List<SubtitleTrack> {
    val stem = mediaStem(mediaFileName)
    return folderFiles
        .filter { isSubtitleFile(it.fileName) }
        .mapNotNull { file ->
            val label = subtitleDisplayName(stem, file.fileName) ?: return@mapNotNull null
            SubtitleTrack(
                documentUri = file.documentUri,
                fileName = file.fileName,
                displayName = label,
            )
        }
        .sortedBy { it.fileName.lowercase() }
}

fun autoSelectTrack(tracks: List<SubtitleTrack>): SubtitleTrack? {
    if (tracks.isEmpty()) return null
    return tracks.find { it.displayName == "同步" } ?: tracks.first()
}
