package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jianqiaofan.subtitleplayer.domain.cloud.NamedText
import com.jianqiaofan.subtitleplayer.domain.cloud.VIDEO_HASH_SUFFIX
import com.jianqiaofan.subtitleplayer.domain.cloud.VideoHashRecord
import com.jianqiaofan.subtitleplayer.domain.cloud.encodeVideoHash
import com.jianqiaofan.subtitleplayer.domain.cloud.hashCacheValid
import com.jianqiaofan.subtitleplayer.domain.cloud.parseVideoHash
import com.jianqiaofan.subtitleplayer.domain.cloud.videoHashFileName
import com.jianqiaofan.subtitleplayer.domain.model.fileExtension
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class CloudFolderFiles(context: Context) {
    private val appContext = context.applicationContext
    private val library = MediaLibrary(appContext)

    fun displayName(uri: Uri): String? = library.displayNameOf(uri)

    fun parentOf(uri: Uri): MediaFolder? = library.locateMediaDirectory(uri)

    fun listHashFiles(treeUri: Uri, directoryUri: Uri): List<NamedText> {
        val directoryId = documentId(directoryUri) ?: return emptyList()
        return library.listFolder(treeUri, directoryId)
            .filter { it.displayName.endsWith(VIDEO_HASH_SUFFIX) && !it.isDirectory }
            .map { NamedText(it.displayName, readText(it.documentUri).orEmpty()) }
    }

    fun readText(uri: Uri): String? {
        return try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            null
        }
    }

    fun ensureVideoHash(treeUri: Uri, directoryUri: Uri, mediaUri: Uri, mediaName: String): String? {
        val stat = stat(mediaUri)
        val sidecarName = videoHashFileName(mediaName)
        val directoryId = documentId(directoryUri) ?: return null
        val existing = library.listFolder(treeUri, directoryId)
            .firstOrNull { it.displayName == sidecarName && !it.isDirectory }
        if (existing != null && stat != null) {
            val record = readText(existing.documentUri)?.let { parseVideoHash(it) }
            if (record != null && hashCacheValid(record, stat.size, stat.modifiedMs)) return record.hash
        }
        val hash = sha256Of(mediaUri) ?: return existing?.let { readText(it.documentUri)?.let { text -> parseVideoHash(text)?.hash } }
        if (stat != null) {
            val record = VideoHashRecord(hash, stat.size, stat.modifiedMs * 1_000_000L)
            writeText(directoryUri, sidecarName, "application/json", encodeVideoHash(record))
        }
        return hash
    }

    fun writeText(parentUri: Uri, displayName: String, mime: String, text: String): Result<Unit> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tree = findTree(parentUri) ?: return Result.failure(IllegalStateException("没有文件夹权限"))
        val directoryId = documentId(parentUri) ?: return Result.failure(IllegalStateException("无法定位文件夹"))
        val existing = library.listFolder(tree, directoryId)
            .firstOrNull { it.displayName == displayName && !it.isDirectory }
        if (existing != null) {
            return if (writeBytes(existing.documentUri, bytes)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("无法写入 $displayName"))
            }
        }
        val created = create(parentUri, displayName, mime)
            ?: create(parentUri, displayName, "text/plain")
            ?: return Result.failure(IllegalStateException("无法创建 $displayName"))
        return if (writeBytes(created, bytes)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("无法写入 $displayName"))
        }
    }

    fun mimeFor(fileName: String): String = when (fileExtension(fileName)) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "json" -> "application/json"
        else -> "text/plain"
    }

    private fun sha256Of(uri: Uri): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Exception) {
            null
        }
    }

    private fun stat(uri: Uri): FileStat? {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (sizeIndex < 0 || timeIndex < 0) return@use null
                val size = cursor.getLong(sizeIndex)
                val modified = cursor.getLong(timeIndex)
                if (size < 0 || modified <= 0L) return@use null
                FileStat(size, modified)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun create(parentUri: Uri, displayName: String, mime: String): Uri? {
        return try {
            DocumentsContract.createDocument(appContext.contentResolver, parentUri, mime, displayName)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Boolean {
        for (mode in listOf("wt", "rwt", "w")) {
            try {
                appContext.contentResolver.openOutputStream(uri, mode)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun documentId(uri: Uri): String? =
        try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            null
        }

    private fun findTree(directoryUri: Uri): Uri? {
        val directoryId = documentId(directoryUri) ?: return null
        return appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }
            .firstOrNull { tree ->
                DocumentLocations.isWithinTree(DocumentsContract.getTreeDocumentId(tree), directoryId)
            }
    }

    private data class FileStat(val size: Long, val modifiedMs: Long)
}
