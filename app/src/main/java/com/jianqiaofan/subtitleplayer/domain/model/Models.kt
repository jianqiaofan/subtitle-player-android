package com.jianqiaofan.subtitleplayer.domain.model

data class SubtitleCue(
    val index: Int,
    val start: Double,
    val end: Double,
    val text: String,
)

data class SubtitleTrack(
    val documentUri: String,
    val fileName: String,
    val displayName: String,
)

enum class SubtitleFormat {
    Srt,
    Vtt,
}

data class MediaEntry(
    val documentUri: String,
    val displayName: String,
    val isAudio: Boolean,
    val durationMs: Long? = null,
    val subtitleCount: Int = 0,
)

data class RecentFolder(
    val treeUri: String,
    val displayName: String,
    val lastOpenedAt: Long,
)

val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v")
val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")

fun fileExtension(name: String): String =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

fun isMediaFile(name: String): Boolean {
    val ext = fileExtension(name)
    return ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS
}

fun isAudioFile(name: String): Boolean = fileExtension(name) in AUDIO_EXTENSIONS

fun isSubtitleFile(name: String): Boolean = fileExtension(name) in SUBTITLE_EXTENSIONS

fun mediaStem(fileName: String): String = fileName.substringBeforeLast('.')

fun subtitleFormatOf(fileName: String): SubtitleFormat? =
    when (fileExtension(fileName)) {
        "srt" -> SubtitleFormat.Srt
        "vtt" -> SubtitleFormat.Vtt
        else -> null
    }
