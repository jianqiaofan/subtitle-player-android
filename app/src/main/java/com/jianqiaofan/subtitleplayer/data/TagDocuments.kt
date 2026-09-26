package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION
import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.TagSyncDecision
import com.jianqiaofan.subtitleplayer.domain.tags.TagSyncKind
import com.jianqiaofan.subtitleplayer.domain.tags.encodeTagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.parseTagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.planTagSync
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleFileNameFromTagFile
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TagDocuments(private val context: Context) {
    private val library = MediaLibrary(context)

    suspend fun readBeside(treeUri: Uri, subtitleFileName: String): TagDocument? =
        readInFolder(treeUri, library.treeDocumentUri(treeUri), subtitleFileName)

    suspend fun readInFolder(treeUri: Uri, directoryUri: Uri, subtitleFileName: String): TagDocument? =
        withContext(Dispatchers.IO) {
            val child = childIn(treeUri, directoryUri, tagFileNameFor(subtitleFileName)) ?: return@withContext null
            readDocument(child.documentUri, subtitleFileName)
        }

    suspend fun saveBeside(treeUri: Uri, subtitleFileName: String, document: TagDocument?): Result<Unit> =
        saveInFolder(treeUri, library.treeDocumentUri(treeUri), subtitleFileName, document)

    suspend fun saveInFolder(
        treeUri: Uri,
        directoryUri: Uri,
        subtitleFileName: String,
        document: TagDocument?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tagName = tagFileNameFor(subtitleFileName)
        val existing = childIn(treeUri, directoryUri, tagName)
        if (document == null || document.entries.isEmpty()) {
            if (existing == null) return@withContext Result.success(Unit)
            val deleted = delete(existing.documentUri)
            return@withContext if (deleted) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("无法删除 $tagName"))
            }
        }
        if (existing == null) {
            createAndWrite(directoryUri, tagName, document)
        } else {
            writeDocument(existing.documentUri, document)
        }
    }

    suspend fun syncIntoTree(treeUri: Uri, sources: List<Uri>): List<TagSyncDecision> =
        syncIntoFolder(treeUri, library.treeDocumentUri(treeUri), sources)

    suspend fun syncIntoFolder(treeUri: Uri, directoryUri: Uri, sources: List<Uri>): List<TagSyncDecision> =
        withContext(Dispatchers.IO) {
            sources.map { source -> syncOne(treeUri, directoryUri, source) }
        }

    fun readDocument(uri: Uri, expectedSubtitleFile: String? = null): TagDocument? {
        val text = readText(uri) ?: return null
        return parseTagDocument(text, expectedSubtitleFile)
    }

    fun readText(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            decodeUtf8Replace(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun syncOne(treeUri: Uri, directoryUri: Uri, source: Uri): TagSyncDecision {
        val name = library.displayNameOf(source) ?: "标签文件"
        val subtitleName = subtitleFileNameFromTagFile(name)
            ?: return TagSyncDecision(TagSyncKind.Skipped, name, "文件名不是字幕标签文件")
        val subtitle = childIn(treeUri, directoryUri, subtitleName)
            ?: return TagSyncDecision(TagSyncKind.Skipped, subtitleName, "当前文件夹没有同名字幕")
        if (subtitle.isDirectory) {
            return TagSyncDecision(TagSyncKind.Skipped, subtitleName, "当前文件夹没有同名字幕")
        }
        val parsed = readText(source)?.let { parseTagDocument(it) }
        val tagName = tagFileNameFor(subtitleName)
        val existing = childIn(treeUri, directoryUri, tagName)
        val sourceIsDestination = existing != null && library.sameDocument(source, existing.documentUri)
        val existingDoc = existing?.let {
            readDocument(it.documentUri) ?: TagDocument(TAG_DOCUMENT_VERSION, subtitleName, emptyList())
        }
        val decision = planTagSync(
            tagFileName = name,
            parsed = parsed,
            destinationSubtitleExists = true,
            destinationTag = existingDoc,
            sourceIsDestination = sourceIsDestination,
        )
        val document = decision.document ?: return decision
        val written = if (existing == null) {
            createAndWrite(directoryUri, tagName, document)
        } else {
            writeDocument(existing.documentUri, document)
        }
        return if (written.isSuccess) {
            decision
        } else {
            TagSyncDecision(TagSyncKind.Skipped, subtitleName, "无法写入 $tagName")
        }
    }

    private fun childIn(treeUri: Uri, directoryUri: Uri, displayName: String): FolderChild? {
        val directoryId = try {
            DocumentsContract.getDocumentId(directoryUri)
        } catch (_: Exception) {
            return null
        }
        return library.listFolder(treeUri, directoryId)
            .firstOrNull { it.displayName == displayName && !it.isDirectory }
    }

    fun writeDocument(uri: Uri, document: TagDocument): Result<Unit> {
        val ok = writeBytes(uri, encodeTagDocument(document).toByteArray(Charsets.UTF_8))
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("无法写入标签文件"))
    }

    fun createAndWrite(parentDocumentUri: Uri, displayName: String, document: TagDocument): Result<Unit> {
        val created = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocumentUri,
                "application/json",
                displayName,
            )
        } catch (_: Exception) {
            null
        } ?: return Result.failure(IllegalStateException("无法创建 $displayName"))
        return writeDocument(created, document)
    }

    fun delete(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            false
        }
    }

    private fun writeBytes(documentUri: Uri, bytes: ByteArray): Boolean {
        val modes = listOf("wt", "rwt", "w")
        for (mode in modes) {
            try {
                context.contentResolver.openOutputStream(documentUri, mode)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun decodeUtf8Replace(bytes: ByteArray): String {
        val decoder = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
