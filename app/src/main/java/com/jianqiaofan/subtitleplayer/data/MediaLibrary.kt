package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.jianqiaofan.subtitleplayer.domain.model.MediaEntry
import com.jianqiaofan.subtitleplayer.domain.model.isAudioFile
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.subtitle.NamedSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.subtitle.findSubtitlesForMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FolderChild(
    val documentUri: Uri,
    val displayName: String,
)

class MediaLibrary(private val context: Context) {
    fun persistTreePermission(treeUri: Uri): Boolean {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(treeUri, flags)
            true
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    fun hasPersistedAccess(treeUri: Uri): Boolean {
        val target = treeUri.toString()
        return context.contentResolver.persistedUriPermissions.any { it.uri.toString() == target && it.isReadPermission }
    }

    fun folderDisplayName(treeUri: Uri): String {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: Uri.decode(treeUri.lastPathSegment.orEmpty()).substringAfterLast(':').ifBlank { "学习文件夹" }
    }

    fun treeIsWritable(treeUri: Uri): Boolean {
        return DocumentFile.fromTreeUri(context, treeUri)?.canWrite() == true
    }

    suspend fun listMedia(treeUri: Uri): List<MediaEntry> = withContext(Dispatchers.IO) {
        val children = listChildren(treeUri)
        val subtitleFiles = children
            .filter { isSubtitleFile(it.displayName) }
            .map { NamedSubtitleFile(it.displayName, it.documentUri.toString()) }
        children
            .filter { isMediaFile(it.displayName) }
            .sortedBy { it.displayName.lowercase() }
            .map { child ->
                MediaEntry(
                    documentUri = child.documentUri.toString(),
                    displayName = child.displayName,
                    isAudio = isAudioFile(child.displayName),
                    durationMs = readDurationMs(child.documentUri),
                    subtitleCount = findSubtitlesForMedia(child.displayName, subtitleFiles).size,
                )
            }
    }

    fun listSubtitleFiles(treeUri: Uri): List<NamedSubtitleFile> {
        return listChildren(treeUri)
            .filter { isSubtitleFile(it.displayName) }
            .map { NamedSubtitleFile(it.displayName, it.documentUri.toString()) }
    }

    private fun listChildren(treeUri: Uri): List<FolderChild> {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val result = mutableListOf<FolderChild>()
        val resolver = context.contentResolver
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                result += FolderChild(docUri, name)
            }
        }
        return result
    }

    private fun readDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
