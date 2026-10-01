package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jianqiaofan.subtitleplayer.domain.bundle.bundleFolderName
import com.jianqiaofan.subtitleplayer.domain.bundle.isBundleFolderName
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import com.jianqiaofan.subtitleplayer.domain.screenshot.SCREENSHOT_DIR
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.imageNameFor
import com.jianqiaofan.subtitleplayer.domain.screenshot.managedRelativePath
import com.jianqiaofan.subtitleplayer.domain.screenshot.resolveScreenshotStamp
import com.jianqiaofan.subtitleplayer.domain.screenshot.sortedScreenshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One valid screenshot found under a manage-folder scan.
 * Validity = video exists + screenshots.json lists it + image file exists.
 */
data class ManagedScreenshot(
    val shot: ScreenshotShot,
    val treeUri: Uri,
    val bundleUri: Uri,
    val mediaUri: Uri,
    val mediaName: String,
    /** Path relative to the selected root, for the list「路径」column. */
    val relativePath: String,
    /** Parent directory of the video, relative to root (empty = root). */
    val relativeDir: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class ScreenshotScanner(context: Context) {
    private val appContext = context.applicationContext
    private val library = MediaLibrary(appContext)
    private val files = ScreenshotFiles(appContext)

    suspend fun scan(treeUri: Uri): List<ManagedScreenshot> = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = mutableListOf<ManagedScreenshot>()
        val queue = ArrayDeque<Pair<String, String>>() // documentId to relativeDir
        queue.add(rootId to "")
        while (queue.isNotEmpty()) {
            val (directoryId, relativeDir) = queue.removeFirst()
            val children = library.listFolder(treeUri, directoryId)
            val media = children
                .filter { !it.isDirectory && isMediaFile(it.displayName) }
                .sortedBy { it.displayName.lowercase() }
            for (video in media) {
                out += shotsForVideo(treeUri, video, relativeDir)
            }
            val subdirs = children
                .filter { it.isDirectory && !isBundleFolderName(it.displayName) }
                .sortedBy { it.displayName.lowercase() }
            for (dir in subdirs) {
                val nextDir = if (relativeDir.isEmpty()) dir.displayName else "$relativeDir/${dir.displayName}"
                val childId = try {
                    DocumentsContract.getDocumentId(dir.documentUri)
                } catch (_: Exception) {
                    continue
                }
                queue.add(childId to nextDir)
            }
        }
        out
    }

    private fun shotsForVideo(
        treeUri: Uri,
        video: FolderChild,
        relativeDir: String,
    ): List<ManagedScreenshot> {
        val parentId = try {
            DocumentsContract.getDocumentId(video.documentUri).let { id ->
                val slash = id.lastIndexOf('/')
                if (slash <= 0) return emptyList()
                id.substring(0, slash)
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val siblings = library.listFolder(treeUri, parentId)
        val bundleName = bundleFolderName(video.displayName)
        val bundle = siblings.firstOrNull { it.isDirectory && it.displayName == bundleName }
            ?: return emptyList()
        val state = files.load(treeUri, bundle.documentUri)
        if (state.unreadable || state.document.screenshots.isEmpty()) return emptyList()
        val result = mutableListOf<ManagedScreenshot>()
        for (shot in sortedScreenshots(state.document.screenshots)) {
            val imageName = shot.image.takeIf { it.isNotBlank() }
                ?: listOf(imageNameFor(shot.id, png = true), imageNameFor(shot.id, png = false))
                    .firstOrNull { it in state.names }
                ?: continue
            if (imageName !in state.names) continue
            val imageUri = childUri(treeUri, bundle.documentUri, SCREENSHOT_DIR, imageName)
            val fileModified = imageUri?.let { lastModified(it) } ?: 0L
            val created = resolveScreenshotStamp(shot.createdAt, fileModified)
            val updated = resolveScreenshotStamp(shot.updatedAt, fileModified)
            result += ManagedScreenshot(
                shot = shot.copy(image = imageName),
                treeUri = treeUri,
                bundleUri = bundle.documentUri,
                mediaUri = video.documentUri,
                mediaName = video.displayName,
                relativePath = managedRelativePath(relativeDir, video.displayName, imageName),
                relativeDir = relativeDir,
                createdAt = created,
                updatedAt = updated,
            )
        }
        return result
    }

    private fun childUri(treeUri: Uri, bundleUri: Uri, vararg names: String): Uri? {
        var current = bundleUri
        for (name in names) {
            val id = try {
                DocumentsContract.getDocumentId(current)
            } catch (_: Exception) {
                return null
            }
            val next = library.listFolder(treeUri, id)
                .firstOrNull { it.displayName == name }
                ?: return null
            current = next.documentUri
        }
        return current
    }

    private fun lastModified(uri: Uri): Long {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index < 0) 0L else cursor.getLong(index).coerceAtLeast(0L)
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
