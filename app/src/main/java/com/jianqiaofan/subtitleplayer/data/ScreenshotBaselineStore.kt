package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.arr
import com.jianqiaofan.subtitleplayer.domain.json.bool
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import java.io.File

/**
 * Remembers which screenshot ids this device last saw for a user and video.
 * Kept in app storage so deleting the video's `.data` folder does not look like a delete.
 */
class ScreenshotBaselineStore(context: Context) {
    private val root = File(context.filesDir, "screenshot-baselines")

    data class Record(
        val ids: List<String>,
        val path: String,
        val wiped: Boolean,
    )

    fun get(username: String, videoHash: String): Record? {
        if (username.isBlank() || videoHash.isBlank()) return null
        return read(username).videos[videoHash]
    }

    fun save(username: String, videoHash: String, ids: List<String>, path: String) {
        if (username.isBlank() || videoHash.isBlank()) return
        synchronized(lock) {
            val book = read(username)
            book.videos[videoHash] = Record(ids, path, wiped = false)
            write(username, book)
        }
    }

    fun markWiped(username: String, videoHash: String, path: String) {
        if (username.isBlank() || videoHash.isBlank()) return
        synchronized(lock) {
            val book = read(username)
            val current = book.videos[videoHash]
            book.videos[videoHash] = Record(current?.ids.orEmpty(), path.ifBlank { current?.path.orEmpty() }, wiped = true)
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
        (root.map["videos"] as? JsonValue.Obj)?.map?.forEach { (hash, value) ->
            val item = value as? JsonValue.Obj ?: return@forEach
            val ids = item.map.arr("ids").orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
            book.videos[hash] = Record(
                ids = ids,
                path = item.map.str("path").orEmpty(),
                wiped = item.map.bool("wiped") == true,
            )
        }
        return book
    }

    private fun write(username: String, book: Book) {
        val file = file(username)
        file.parentFile?.mkdirs()
        file.writeText(encode(book))
    }

    private fun encode(book: Book): String = buildString {
        append("{\"videos\":{")
        book.videos.entries.forEachIndexed { index, (hash, record) ->
            if (index > 0) append(',')
            append(jsonString(hash))
            append(":{\"ids\":[")
            record.ids.forEachIndexed { idIndex, id ->
                if (idIndex > 0) append(',')
                append(jsonString(id))
            }
            append("],\"path\":${jsonString(record.path)},\"wiped\":${record.wiped}}")
        }
        append("}}")
    }

    private fun file(username: String): File {
        val safe = username.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").ifBlank { "user" }
        return File(root, "$safe.json")
    }

    private class Book(val videos: MutableMap<String, Record> = linkedMapOf())

    private val lock = Any()
}
