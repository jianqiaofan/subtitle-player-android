package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudTagDocument
import com.jianqiaofan.subtitleplayer.domain.cloud.parseCloudTagDocument
import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import java.io.File

class CloudBaselineStore(context: Context) {
    private val root = File(context.filesDir, "cloud-baselines")

    data class SubtitleMark(val contentHash: String, val updatedAt: String)
    data class TagMark(val contentHash: String, val updatedAt: String, val document: CloudTagDocument)

    fun subtitle(username: String, videoHash: String, suffix: String): SubtitleMark? {
        val item = read(username).subtitles[key(videoHash, suffix)] ?: return null
        if (item.contentHash.isBlank()) return null
        return SubtitleMark(item.contentHash, item.updatedAt)
    }

    fun tag(username: String, videoHash: String, suffix: String): TagMark? {
        val item = read(username).tags[key(videoHash, suffix)] ?: return null
        val document = item.document ?: return null
        if (item.contentHash.isBlank()) return null
        return TagMark(item.contentHash, item.updatedAt, document)
    }

    fun saveSubtitle(username: String, videoHash: String, suffix: String, contentHash: String, updatedAt: String) {
        if (username.isBlank() || contentHash.isBlank()) return
        synchronized(lock) {
            val book = read(username)
            book.subtitles[key(videoHash, suffix)] = Mark(contentHash, updatedAt, null)
            write(username, book)
        }
    }

    fun saveTag(
        username: String,
        videoHash: String,
        suffix: String,
        contentHash: String,
        updatedAt: String,
        document: CloudTagDocument,
    ) {
        if (username.isBlank() || contentHash.isBlank()) return
        synchronized(lock) {
            val book = read(username)
            book.tags[key(videoHash, suffix)] = Mark(contentHash, updatedAt, document)
            write(username, book)
        }
    }

    private fun read(username: String): Book {
        val file = file(username)
        if (!file.isFile) return Book()
        val root = try {
            parseJson(file.readText()) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: return Book()
        val book = Book()
        (root.map["subtitles"] as? JsonValue.Obj)?.map?.forEach { (key, value) ->
            val item = value as? JsonValue.Obj ?: return@forEach
            book.subtitles[key] = Mark(item.map.str("content_hash").orEmpty(), item.map.str("updated_at").orEmpty(), null)
        }
        (root.map["tags"] as? JsonValue.Obj)?.map?.forEach { (key, value) ->
            val item = value as? JsonValue.Obj ?: return@forEach
            val document = (item.map["document"] as? JsonValue.Str)?.value?.let { parseCloudTagDocument(it) }
            book.tags[key] = Mark(item.map.str("content_hash").orEmpty(), item.map.str("updated_at").orEmpty(), document)
        }
        return book
    }

    private fun write(username: String, book: Book) {
        val file = file(username)
        file.parentFile?.mkdirs()
        file.writeText(encode(book))
    }

    private fun encode(book: Book): String = buildString {
        append("{\"subtitles\":{")
        book.subtitles.entries.forEachIndexed { index, (key, mark) ->
            if (index > 0) append(',')
            append(jsonString(key))
            append(":{\"content_hash\":${jsonString(mark.contentHash)},\"updated_at\":${jsonString(mark.updatedAt)}}")
        }
        append("},\"tags\":{")
        book.tags.entries.forEachIndexed { index, (key, mark) ->
            if (index > 0) append(',')
            append(jsonString(key))
            append(":{\"content_hash\":${jsonString(mark.contentHash)},\"updated_at\":${jsonString(mark.updatedAt)},")
            append("\"document\":${jsonString(com.jianqiaofan.subtitleplayer.domain.cloud.encodeCloudTagDocument(mark.document ?: CloudTagDocument("", emptyList())))}}")
        }
        append("}}")
    }

    private fun file(username: String): File {
        val safe = username.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").ifBlank { "user" }
        return File(root, "$safe.json")
    }

    private fun key(videoHash: String, suffix: String) = "$videoHash|$suffix"

    private class Book(
        val subtitles: MutableMap<String, Mark> = linkedMapOf(),
        val tags: MutableMap<String, Mark> = linkedMapOf(),
    )

    private data class Mark(val contentHash: String, val updatedAt: String, val document: CloudTagDocument?)

    private val lock = Any()
}
