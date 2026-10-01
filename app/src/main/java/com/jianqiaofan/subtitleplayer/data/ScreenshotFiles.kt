package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jianqiaofan.subtitleplayer.domain.screenshot.SCREENSHOT_DIR
import com.jianqiaofan.subtitleplayer.domain.screenshot.SCREENSHOT_MANIFEST
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotDocument
import com.jianqiaofan.subtitleplayer.domain.screenshot.encodeScreenshotDocument
import com.jianqiaofan.subtitleplayer.domain.screenshot.parseScreenshotDocument

data class ScreenshotFolderState(
    val manifestPresent: Boolean,
    val unreadable: Boolean,
    val document: ScreenshotDocument,
    val names: Set<String>,
)

class ScreenshotFiles(context: Context) {
    private val appContext = context.applicationContext
    private val library = MediaLibrary(appContext)

    fun load(treeUri: Uri, bundleUri: Uri): ScreenshotFolderState {
        val folder = screenshotDir(treeUri, bundleUri) ?: return ScreenshotFolderState(false, false, ScreenshotDocument(), emptySet())
        val names = children(treeUri, folder).filter { !it.isDirectory }.map { it.displayName }.toSet()
        if (SCREENSHOT_MANIFEST !in names) {
            return ScreenshotFolderState(false, false, ScreenshotDocument(), names)
        }
        val text = readText(childUri(treeUri, folder, SCREENSHOT_MANIFEST))
        if (text == null) return ScreenshotFolderState(true, true, ScreenshotDocument(), names)
        val document = parseScreenshotDocument(text)
            ?: return ScreenshotFolderState(true, true, ScreenshotDocument(), names)
        return ScreenshotFolderState(true, false, document, names)
    }

    fun write(treeUri: Uri, bundleUri: Uri, document: ScreenshotDocument): Boolean {
        if (document.screenshots.isEmpty()) {
            clear(treeUri, bundleUri)
            return true
        }
        val folder = ensureDir(treeUri, bundleUri) ?: return false
        val wrote = writeBytes(
            childUri(treeUri, folder, SCREENSHOT_MANIFEST) ?: create(folder, SCREENSHOT_MANIFEST, "application/json") ?: return false,
            encodeScreenshotDocument(document).toByteArray(Charsets.UTF_8),
        )
        if (!wrote) return false
        val listed = children(treeUri, folder)
        val keep = document.screenshots.map { it.id }.toSet()
        val pngIds = listed.mapNotNull { child ->
            if (!child.isDirectory && child.displayName.endsWith(".png", true)) {
                child.displayName.substringBeforeLast('.')
            } else {
                null
            }
        }.toSet()
        for (child in listed) {
            if (child.isDirectory || child.displayName == SCREENSHOT_MANIFEST) continue
            val stem = child.displayName.substringBeforeLast('.')
            val ext = child.displayName.substringAfterLast('.', "")
            val orphan = (ext.equals("png", true) || ext.equals("webp", true)) && stem !in keep
            val duplicateWebp = ext.equals("webp", true) && stem in pngIds
            if (orphan || duplicateWebp) delete(child.documentUri)
        }
        return true
    }

    fun readImage(treeUri: Uri, bundleUri: Uri, fileName: String): ByteArray? {
        val folder = screenshotDir(treeUri, bundleUri) ?: return null
        val uri = childUri(treeUri, folder, fileName) ?: return null
        return readBytes(uri)
    }

    fun writeImage(treeUri: Uri, bundleUri: Uri, fileName: String, mime: String, bytes: ByteArray): Boolean {
        val folder = ensureDir(treeUri, bundleUri) ?: return false
        val existing = childUri(treeUri, folder, fileName)
        val uri = existing ?: create(folder, fileName, mime) ?: return false
        return writeBytes(uri, bytes)
    }

    fun deleteImage(treeUri: Uri, bundleUri: Uri, fileName: String) {
        val folder = screenshotDir(treeUri, bundleUri) ?: return
        childUri(treeUri, folder, fileName)?.let { delete(it) }
    }

    fun clear(treeUri: Uri, bundleUri: Uri) {
        val folder = screenshotDir(treeUri, bundleUri) ?: return
        for (child in children(treeUri, folder)) {
            delete(child.documentUri)
        }
        delete(folder)
    }

    private fun screenshotDir(treeUri: Uri, bundleUri: Uri): Uri? {
        val bundleId = documentId(bundleUri) ?: return null
        return library.listFolder(treeUri, bundleId)
            .firstOrNull { it.isDirectory && it.displayName == SCREENSHOT_DIR }
            ?.documentUri
    }

    private fun ensureDir(treeUri: Uri, bundleUri: Uri): Uri? {
        screenshotDir(treeUri, bundleUri)?.let { return it }
        if (!library.treeIsWritable(treeUri)) return null
        return try {
            DocumentsContract.createDocument(
                appContext.contentResolver,
                bundleUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                SCREENSHOT_DIR,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun children(treeUri: Uri, folderUri: Uri): List<FolderChild> {
        val id = documentId(folderUri) ?: return emptyList()
        return library.listFolder(treeUri, id)
    }

    private fun childUri(treeUri: Uri, folderUri: Uri, name: String): Uri? =
        children(treeUri, folderUri).firstOrNull { it.displayName == name && !it.isDirectory }?.documentUri

    private fun create(parent: Uri, name: String, mime: String): Uri? = try {
        DocumentsContract.createDocument(appContext.contentResolver, parent, mime, name)
    } catch (_: Exception) {
        null
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

    private fun readBytes(uri: Uri): ByteArray? = try {
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun readText(uri: Uri?): String? {
        if (uri == null) return null
        val bytes = readBytes(uri) ?: return null
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        } catch (_: Exception) {
        }
    }

    private fun documentId(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        null
    }
}
