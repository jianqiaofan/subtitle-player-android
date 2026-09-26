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
    val isDirectory: Boolean = false,
)

data class MediaFolder(
    val treeUri: Uri,
    val directoryUri: Uri,
    val directoryId: String,
    val directoryName: String,
)

data class TreeFile(
    val name: String,
    val documentUri: Uri,
    val parentDocumentUri: Uri,
    val relativeDir: String,
    val isDirectory: Boolean,
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
        return listFolder(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            .filter { isSubtitleFile(it.displayName) }
            .map { NamedSubtitleFile(it.displayName, it.documentUri.toString()) }
    }

    fun listSubtitleFilesIn(treeUri: Uri, directoryUri: Uri): List<NamedSubtitleFile> {
        val directoryId = try {
            DocumentsContract.getDocumentId(directoryUri)
        } catch (_: Exception) {
            return emptyList()
        }
        return listFolder(treeUri, directoryId)
            .filter { isSubtitleFile(it.displayName) }
            .map { NamedSubtitleFile(it.displayName, it.documentUri.toString()) }
    }

    fun listFolder(treeUri: Uri, directoryDocumentId: String): List<FolderChild> {
        return try {
            listChildrenOf(treeUri, directoryDocumentId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun locateMediaDirectory(mediaUri: Uri): MediaFolder? {
        val trees = persistedTreeUris()
        if (trees.isEmpty()) return null
        val byId = locateByDocumentId(mediaUri, trees)
        if (byId != null) return byId
        val path = try {
            DocumentsContract.findDocumentPath(context.contentResolver, mediaUri)
        } catch (_: Exception) {
            null
        } ?: return null
        val ids = path.path
        if (ids.size < 2) return null
        val parentId = ids[ids.size - 2]
        val tree = trees.firstOrNull { treeUri ->
            DocumentLocations.isWithinTree(DocumentsContract.getTreeDocumentId(treeUri), parentId)
        } ?: return null
        return mediaFolder(tree, parentId)
    }

    private fun locateByDocumentId(mediaUri: Uri, trees: List<Uri>): MediaFolder? {
        val documentId = try {
            DocumentsContract.getDocumentId(mediaUri)
        } catch (_: Exception) {
            return null
        }
        val parentId = DocumentLocations.parentId(documentId) ?: return null
        val tree = trees.firstOrNull { treeUri ->
            DocumentLocations.isWithinTree(DocumentsContract.getTreeDocumentId(treeUri), parentId)
        } ?: return null
        return mediaFolder(tree, parentId)
    }

    private fun mediaFolder(treeUri: Uri, directoryId: String): MediaFolder {
        return MediaFolder(
            treeUri = treeUri,
            directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, directoryId),
            directoryId = directoryId,
            directoryName = DocumentLocations.directoryLabel(directoryId),
        )
    }

    private fun persistedTreeUris(): List<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }
    }

    fun treeDocumentUri(treeUri: Uri): Uri {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
    }

    fun findChild(treeUri: Uri, displayName: String): FolderChild? =
        listChildren(treeUri).firstOrNull { it.displayName == displayName }

    fun listTreeFiles(treeUri: Uri, maxDepth: Int = 8): List<TreeFile> {
        val root = treeDocumentUri(treeUri)
        val out = mutableListOf<TreeFile>()
        walk(treeUri, root, "", 0, maxDepth, out)
        return out
    }

    fun documentExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        }
    }

    fun displayNameOf(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    fun sameDocument(left: Uri, right: Uri): Boolean {
        return try {
            DocumentsContract.getDocumentId(left) == DocumentsContract.getDocumentId(right)
        } catch (_: Exception) {
            left.toString() == right.toString()
        }
    }

    private fun walk(
        treeUri: Uri,
        parentUri: Uri,
        relativeDir: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<TreeFile>,
    ) {
        val parentId = try {
            DocumentsContract.getDocumentId(parentUri)
        } catch (_: Exception) {
            return
        }
        for (child in listChildrenOf(treeUri, parentId)) {
            out += TreeFile(
                name = child.displayName,
                documentUri = child.documentUri,
                parentDocumentUri = parentUri,
                relativeDir = relativeDir,
                isDirectory = child.isDirectory,
            )
            if (child.isDirectory && depth + 1 < maxDepth) {
                val nextDir = if (relativeDir.isEmpty()) child.displayName else "$relativeDir/${child.displayName}"
                walk(treeUri, child.documentUri, nextDir, depth + 1, maxDepth, out)
            }
        }
    }

    private fun listChildrenOf(treeUri: Uri, documentId: String): List<FolderChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val result = mutableListOf<FolderChild>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0) return@use
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                result += FolderChild(
                    documentUri = docUri,
                    displayName = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return result
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
