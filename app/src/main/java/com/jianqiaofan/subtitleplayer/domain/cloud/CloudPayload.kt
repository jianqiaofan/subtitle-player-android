package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.arr
import com.jianqiaofan.subtitleplayer.domain.json.bool
import com.jianqiaofan.subtitleplayer.domain.json.obj
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import com.jianqiaofan.subtitleplayer.domain.time.utcMillis

fun parseAuthSession(body: String): AuthSession? {
    val map = jsonObject(body) ?: return null
    val token = map.str("access_token").orEmpty()
    val username = map.str("username").orEmpty()
    if (token.isBlank() || username.isBlank()) return null
    return AuthSession(token, username)
}

fun parseErrorDetail(body: String): String {
    val map = jsonObject(body)
    val detail = map?.get("detail")
    val text = when (detail) {
        is JsonValue.Str -> detail.value
        is JsonValue.Arr -> detail.items.mapNotNull { item ->
            when (item) {
                is JsonValue.Str -> item.value
                is JsonValue.Obj -> item.map.str("msg")
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct().joinToString("；")
        else -> ""
    }
    return text.ifBlank { "请求失败" }
}

fun parseSyncSnapshot(body: String): SyncSnapshot? {
    val map = jsonObject(body) ?: return null
    val hash = map.str("video_hash").orEmpty()
    val subtitles = map.arr("subtitles").orEmpty().mapNotNull { node ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        RemoteSubtitle(
            subtitleName = item.str("subtitle_name").orEmpty(),
            suffix = item.str("subtitle_suffix").orEmpty(),
            content = item.str("content").orEmpty(),
            contentHash = item.str("content_hash").orEmpty(),
            updatedAt = item.str("updated_at").orEmpty(),
        )
    }
    val tags = map.arr("tags").orEmpty().mapNotNull { node ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        val document = item.obj("document")?.let { cloudTagDocument(it) } ?: return@mapNotNull null
        RemoteTag(
            subtitleName = item.str("subtitle_name").orEmpty(),
            suffix = item.str("subtitle_suffix").orEmpty(),
            contentHash = item.str("content_hash").orEmpty(),
            updatedAt = item.str("updated_at").orEmpty(),
            document = document,
        )
    }
    return SyncSnapshot(hash, subtitles, tags)
}

fun parseLibrarySubtitles(body: String): List<LibrarySubtitle>? {
    val map = jsonObject(body) ?: return null
    return map.arr("subtitles")?.mapNotNull { node ->
        librarySubtitle((node as? JsonValue.Obj)?.map ?: return@mapNotNull null)
    }
}

fun parseLibrarySubtitle(body: String): LibrarySubtitle? =
    librarySubtitle(jsonObject(body) ?: return null)

fun parseLibraryTags(body: String): List<LibraryTag>? {
    val map = jsonObject(body) ?: return null
    return map.arr("tags")?.mapNotNull { node ->
        libraryTag((node as? JsonValue.Obj)?.map ?: return@mapNotNull null)
    }
}

fun parseLibraryTag(body: String): LibraryTag? = libraryTag(jsonObject(body) ?: return null)

fun parseSavedSubtitle(body: String): SavedSubtitle? {
    val map = jsonObject(body) ?: return null
    return SavedSubtitle(
        contentHash = map.str("content_hash").orEmpty(),
        updatedAt = map.str("updated_at").orEmpty(),
        suffix = map.str("subtitle_suffix").orEmpty(),
    ).takeIf { it.contentHash.isNotBlank() }
}

fun parseSavedTag(body: String): SavedTag? {
    val map = jsonObject(body) ?: return null
    val document = map.obj("document")?.let { cloudTagDocument(it) } ?: return null
    val saved = SavedTag(
        contentHash = map.str("content_hash").orEmpty(),
        updatedAt = map.str("updated_at").orEmpty(),
        suffix = map.str("subtitle_suffix").orEmpty(),
        document = document,
    )
    return saved.takeIf { it.contentHash.isNotBlank() }
}

fun parseShares(body: String): List<SharePerson>? {
    val map = jsonObject(body) ?: return null
    val people = map.arr("shares").orEmpty().mapNotNull { node ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        val username = item.str("username").orEmpty()
        if (username.isBlank()) return@mapNotNull null
        val subtitles = item.arr("subtitles").orEmpty().mapNotNull { child ->
            val sub = (child as? JsonValue.Obj)?.map ?: return@mapNotNull null
            ShareSubtitle(
                subtitleName = sub.str("subtitle_name").orEmpty(),
                suffix = sub.str("subtitle_suffix").orEmpty(),
                contentHash = sub.str("content_hash").orEmpty(),
                updatedAt = sub.str("updated_at").orEmpty(),
                content = sub.str("content"),
            )
        }
        SharePerson(username, item.str("updated_at").orEmpty(), subtitles)
    }
    return people.sortedByDescending { utcMillis(it.updatedAt) ?: Long.MIN_VALUE }
}

private fun librarySubtitle(map: Map<String, JsonValue>): LibrarySubtitle? {
    val id = (map["id"] as? JsonValue.Num)?.longOrNull() ?: return null
    return LibrarySubtitle(
        id = id,
        videoHash = map.str("video_hash").orEmpty(),
        videoStem = map.str("video_stem").orEmpty(),
        subtitleName = map.str("subtitle_name").orEmpty(),
        suffix = map.str("subtitle_suffix").orEmpty(),
        shared = map.bool("shared") == true,
        updatedAt = map.str("updated_at").orEmpty(),
        content = map.str("content"),
    )
}

private fun libraryTag(map: Map<String, JsonValue>): LibraryTag? {
    val id = (map["id"] as? JsonValue.Num)?.longOrNull() ?: return null
    return LibraryTag(
        id = id,
        videoHash = map.str("video_hash").orEmpty(),
        videoStem = map.str("video_stem").orEmpty(),
        subtitleName = map.str("subtitle_name").orEmpty(),
        suffix = map.str("subtitle_suffix").orEmpty(),
        updatedAt = map.str("updated_at").orEmpty(),
        document = map.obj("document")?.let { cloudTagDocument(it) },
    )
}

private fun jsonObject(body: String): Map<String, JsonValue>? =
    try {
        (parseJson(body) as? JsonValue.Obj)?.map
    } catch (_: Exception) {
        null
    }
