package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleFormat
import com.jianqiaofan.subtitleplayer.domain.subtitle.cuesToFileContent
import com.jianqiaofan.subtitleplayer.domain.subtitle.loadSubtitleContent
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubtitleDocuments(private val context: Context) {
    suspend fun readCues(documentUri: Uri, format: SubtitleFormat): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(documentUri)?.use { it.readBytes() }
                ?: return@withContext emptyList()
            loadSubtitleContent(decodeUtf8Replace(bytes), format)
        }

    suspend fun writeCues(documentUri: Uri, cues: List<SubtitleCue>, format: SubtitleFormat): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = cuesToFileContent(cues, format).toByteArray(Charsets.UTF_8)
            val written = writeBytes(documentUri, body)
            if (written) Result.success(Unit) else Result.failure(IllegalStateException("无法写入字幕文件"))
        }

    fun canWrite(documentUri: Uri): Boolean {
        return DocumentFile.fromSingleUri(context, documentUri)?.canWrite() == true
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
