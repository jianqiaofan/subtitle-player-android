package com.jianqiaofan.subtitleplayer.domain.cloud

data class RemoteSubtitle(
    val subtitleName: String,
    val suffix: String,
    val content: String,
    val contentHash: String,
    val updatedAt: String,
)

data class RemoteTag(
    val subtitleName: String,
    val suffix: String,
    val contentHash: String,
    val updatedAt: String,
    val document: CloudTagDocument,
)

data class SyncSnapshot(
    val videoHash: String,
    val subtitles: List<RemoteSubtitle>,
    val tags: List<RemoteTag>,
)

data class LibrarySubtitle(
    val id: Long,
    val videoHash: String,
    val videoStem: String,
    val subtitleName: String,
    val suffix: String,
    val shared: Boolean,
    val updatedAt: String,
    val content: String? = null,
)

data class LibraryTag(
    val id: Long,
    val videoHash: String,
    val videoStem: String,
    val subtitleName: String,
    val suffix: String,
    val updatedAt: String,
    val document: CloudTagDocument? = null,
)

data class ShareSubtitle(
    val subtitleName: String,
    val suffix: String,
    val contentHash: String,
    val updatedAt: String,
    val content: String? = null,
)

data class SharePerson(
    val username: String,
    val updatedAt: String,
    val subtitles: List<ShareSubtitle>,
)

data class AuthSession(val accessToken: String, val username: String)

data class SavedSubtitle(val contentHash: String, val updatedAt: String, val suffix: String)

data class SavedTag(
    val contentHash: String,
    val updatedAt: String,
    val suffix: String,
    val document: CloudTagDocument,
)

data class CloudOfferLine(val fileName: String, val timeLabel: String)

data class FileCopyInfo(
    val path: String,
    val createdLabel: String,
    val modifiedLabel: String,
)

sealed class CloudPrompt {
    data class Subtitles(val lines: List<CloudOfferLine>) : CloudPrompt()
    data class Tags(val lines: List<CloudOfferLine>) : CloudPrompt()
    data class Shares(val people: List<SharePerson>) : CloudPrompt()
    data class SubtitleConflict(
        val fileName: String,
        val bundle: FileCopyInfo,
        val beside: FileCopyInfo,
    ) : CloudPrompt()
}

sealed class CloudAnswer {
    data object Accept : CloudAnswer()
    data object Dismiss : CloudAnswer()
    data class Person(val username: String) : CloudAnswer()
    data class Keep(val keepBundle: Boolean) : CloudAnswer()
}
