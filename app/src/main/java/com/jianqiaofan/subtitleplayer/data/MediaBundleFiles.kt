package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jianqiaofan.subtitleplayer.domain.bundle.planBundleMigration
import com.jianqiaofan.subtitleplayer.domain.bundle.resolveNameConflict
import com.jianqiaofan.subtitleplayer.domain.bundle.bundleFolderName
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudAnswer
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudPrompt
import com.jianqiaofan.subtitleplayer.domain.cloud.FileCopyInfo
import com.jianqiaofan.subtitleplayer.domain.model.fileExtension
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PLAYBACK_LOG_FILE
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PlaybackLog
import com.jianqiaofan.subtitleplayer.domain.playbacklog.encodePlaybackLog
import com.jianqiaofan.subtitleplayer.domain.playbacklog.formatDeviceTime
import com.jianqiaofan.subtitleplayer.domain.playbacklog.parsePlaybackLog
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaBundleFiles(context: Context) {
    private val appContext = context.applicationContext
    private val library = MediaLibrary(appContext)
    private val folders = CloudFolderFiles(appContext)

    /**
     * Creates `视频全名.data` when the tree is writable, moves old sidecar files in,
     * and asks about same-name subtitles. Returns the directory that holds this video's subtitles.
     */
    suspend fun prepare(
        treeUri: Uri,
        directoryUri: Uri,
        videoFileName: String,
        ask: suspend (CloudPrompt) -> CloudAnswer,
    ): Uri {
        val prepared = withContext(Dispatchers.IO) { migrate(treeUri, directoryUri, videoFileName) }
        val bundle = prepared.bundleUri ?: return directoryUri
        for (conflict in prepared.conflicts) {
            val answer = ask(conflict)
            if (answer is CloudAnswer.Keep) {
                withContext(Dispatchers.IO) {
                    applyConflict(treeUri, directoryUri, bundle, conflict.fileName, answer.keepBundle)
                }
            }
        }
        return bundle
    }

    fun readPlayback(directoryUri: Uri): PlaybackLog {
        val file = child(directoryUri, PLAYBACK_LOG_FILE) ?: return PlaybackLog()
        return parsePlaybackLog(folders.readText(file.documentUri).orEmpty())
    }

    fun writePlayback(directoryUri: Uri, log: PlaybackLog): Boolean =
        folders.writeText(directoryUri, PLAYBACK_LOG_FILE, "application/json", encodePlaybackLog(log)).isSuccess

    private fun migrate(treeUri: Uri, directoryUri: Uri, videoFileName: String): Prepared {
        val parentId = documentId(directoryUri) ?: return Prepared(null, emptyList())
        val children = library.listFolder(treeUri, parentId)
        val files = children.filter { !it.isDirectory }
        val bundle = ensureBundle(treeUri, directoryUri, children, videoFileName) ?: return Prepared(null, emptyList())
        val bundleId = documentId(bundle) ?: return Prepared(bundle, emptyList())
        val inside = library.listFolder(treeUri, bundleId).filter { !it.isDirectory }
        val plan = planBundleMigration(
            videoFileName,
            files.map { it.displayName },
            inside.map { it.displayName },
        )
        for (name in plan.moveIntoBundle) {
            val source = files.find { it.displayName == name } ?: continue
            moveFile(source.documentUri, directoryUri, bundle, name)
        }
        for (name in plan.deleteBeside) {
            val source = files.find { it.displayName == name } ?: continue
            delete(source.documentUri)
        }
        val bundled = library.listFolder(treeUri, bundleId)
        val conflicts = plan.conflicts.map { name ->
            val beside = files.find { it.displayName == name }
            val kept = bundled.find { it.displayName == name && !it.isDirectory }
            if (beside == null || kept == null) return@map null
            CloudPrompt.SubtitleConflict(name, stamp(kept), stamp(beside))
        }.filterNotNull()
        return Prepared(bundle, conflicts)
    }

    private fun applyConflict(
        treeUri: Uri,
        directoryUri: Uri,
        bundleUri: Uri,
        subtitleFileName: String,
        keepBundleCopy: Boolean,
    ) {
        val parentId = documentId(directoryUri) ?: return
        val bundleId = documentId(bundleUri) ?: return
        val besideNames = library.listFolder(treeUri, parentId).filter { !it.isDirectory }
        val bundleNames = library.listFolder(treeUri, bundleId).filter { !it.isDirectory }
        val tag = tagFileNameFor(subtitleFileName)
        val resolution = resolveNameConflict(
            subtitleFileName,
            keepBundleCopy,
            bundleHasTag = bundleNames.any { it.displayName == tag },
            besideHasTag = besideNames.any { it.displayName == tag },
        )
        for (name in resolution.deleteInBundle) {
            bundleNames.find { it.displayName == name }?.let { delete(it.documentUri) }
        }
        for (name in resolution.deleteBeside) {
            besideNames.find { it.displayName == name }?.let { delete(it.documentUri) }
        }
        for (name in resolution.moveIntoBundle) {
            val source = besideNames.find { it.displayName == name } ?: continue
            moveFile(source.documentUri, directoryUri, bundleUri, name)
        }
    }

    private fun ensureBundle(
        treeUri: Uri,
        directoryUri: Uri,
        children: List<FolderChild>,
        videoFileName: String,
    ): Uri? {
        val name = bundleFolderName(videoFileName)
        children.find { it.isDirectory && it.displayName == name }?.let { return it.documentUri }
        if (!library.treeIsWritable(treeUri)) return null
        return try {
            DocumentsContract.createDocument(
                appContext.contentResolver,
                directoryUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun child(directoryUri: Uri, name: String): FolderChild? {
        val tree = findTree(directoryUri) ?: return null
        val directoryId = documentId(directoryUri) ?: return null
        return library.listFolder(tree, directoryId).find { it.displayName == name && !it.isDirectory }
    }

    private fun moveFile(source: Uri, sourceParent: Uri, targetParent: Uri, name: String): Boolean {
        try {
            val moved = DocumentsContract.moveDocument(
                appContext.contentResolver,
                source,
                sourceParent,
                targetParent,
            )
            if (moved != null) return true
        } catch (_: Exception) {
        }
        val bytes = try {
            appContext.contentResolver.openInputStream(source)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return false
        val created = try {
            DocumentsContract.createDocument(appContext.contentResolver, targetParent, mimeFor(name), name)
                ?: DocumentsContract.createDocument(appContext.contentResolver, targetParent, "text/plain", name)
        } catch (_: Exception) {
            null
        } ?: return false
        val wrote = writeBytes(created, bytes)
        if (!wrote) return false
        delete(source)
        return true
    }

    private fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        } catch (_: Exception) {
        }
    }

    private fun stamp(child: FolderChild): FileCopyInfo {
        val (created, modified) = times(child.documentUri)
        return FileCopyInfo(
            path = documentPath(child.documentUri, child.displayName),
            createdLabel = timeLabel(created),
            modifiedLabel = timeLabel(modified),
        )
    }

    private fun timeLabel(millis: Long?): String =
        if (millis == null || millis <= 0L) "未知" else formatDeviceTime(millis)

    private fun times(uri: Uri): Pair<Long?, Long?> {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else null
                val createdIndex = listOf("date_added", "datetaken", "_created").firstNotNullOfOrNull { name ->
                    val index = cursor.getColumnIndex(name)
                    if (index >= 0) index else null
                }
                val createdRaw = createdIndex?.let { cursor.getLong(it) }
                val created = createdRaw?.let { if (it in 1..9_999_999_999L) it * 1000L else it }
                created to modified
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    private fun documentPath(uri: Uri, displayName: String): String {
        val id = documentId(uri) ?: return displayName
        val decoded = try {
            URLDecoder.decode(id, Charsets.UTF_8.name())
        } catch (_: Exception) {
            id
        }
        return decoded.substringAfter(':').ifBlank { displayName }
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

    private fun mimeFor(name: String): String = when (fileExtension(name)) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "json" -> "application/json"
        else -> "text/plain"
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

    private data class Prepared(val bundleUri: Uri?, val conflicts: List<CloudPrompt.SubtitleConflict>)
}
