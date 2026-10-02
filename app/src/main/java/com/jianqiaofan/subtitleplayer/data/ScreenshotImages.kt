package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.jianqiaofan.subtitleplayer.domain.screenshot.MAX_WEBP_BYTES
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_COLOR_BLACK
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_LINE_HEIGHT_MULT
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.WEBP_MAX_EDGE
import com.jianqiaofan.subtitleplayer.domain.screenshot.WEBP_QUALITY
import com.jianqiaofan.subtitleplayer.domain.screenshot.displayBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.fittedEdge
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeVAlign
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteNumberLabel
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

fun grabVideoFrame(context: Context, mediaUri: Uri, timeMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, mediaUri)
        val micros = timeMs.coerceAtLeast(0L) * 1_000L
        retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

fun encodePng(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

fun encodeWebp(source: Bitmap): ByteArray {
    var maxEdge = WEBP_MAX_EDGE
    var quality = WEBP_QUALITY
    var best = ByteArray(0)
    var guard = 0
    while (guard < 8) {
        guard += 1
        val (width, height) = fittedEdge(source.width, source.height, maxEdge)
        val scaled = scale(source, width, height)
        best = compressWebp(scaled, quality)
        if (scaled !== source) scaled.recycle()
        if (best.size <= MAX_WEBP_BYTES) return best
        if (maxOf(width, height) > 480) {
            maxEdge = (maxOf(width, height) * 0.8).roundToInt()
        } else if (quality > 15) {
            quality -= 5
        } else {
            return best
        }
    }
    return best
}

fun renderAnnotatedJpeg(source: Bitmap, notes: List<ScreenshotNote>, showNotes: Boolean): ByteArray {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    try {
        if (showNotes) drawNotes(Canvas(bitmap), bitmap.width, bitmap.height, notes)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

private fun drawNotes(canvas: Canvas, width: Int, height: Int, notes: List<ScreenshotNote>) {
    for (note in notes) {
        val box = displayBox(note)
        val left = (box.x * width).toFloat()
        val top = (box.y * height).toFloat()
        val boxWidth = (box.width * width).toFloat().coerceAtLeast(1f)
        val boxHeight = (box.height * height).toFloat().coerceAtLeast(1f)
        val bodyAlpha = (box.opacity.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseAndroidColor(box.background)
            alpha = bodyAlpha
            style = Paint.Style.FILL
        }
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, background)

        val pad = height * 0.012f
        val titleLabel = noteNumberLabel(notes, note.id)
        val titleSize = (box.font * height * 0.72f).toFloat().coerceAtLeast(8f)
        val titleStyle = when {
            box.titleBold && box.titleItalic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            box.titleBold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            box.titleItalic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseAndroidColor(box.color.ifBlank { NOTE_COLOR_BLACK })
            textSize = titleSize
            typeface = titleStyle
            alpha = (0.92f * 255).roundToInt()
        }
        val titleLayoutWidth = (boxWidth - pad * 2).roundToInt().coerceAtLeast(1)
        val titleLayout = StaticLayout.Builder.obtain(titleLabel, 0, titleLabel.length, titlePaint, titleLayoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, NOTE_LINE_HEIGHT_MULT)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()
        val titleBarH = (titleLayout.height + pad).coerceAtMost(boxHeight * 0.45f)
        val titleBgHex = box.titleBackground.trim()
        if (titleBgHex.isNotEmpty()) {
            val titleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseAndroidColor(titleBgHex)
                alpha = bodyAlpha
                style = Paint.Style.FILL
            }
            canvas.drawRect(left, top, left + boxWidth, top + titleBarH, titleBg)
        }
        canvas.save()
        canvas.translate(left + pad, top + pad * 0.4f)
        canvas.clipRect(0f, 0f, titleLayoutWidth.toFloat(), titleBarH)
        titleLayout.draw(canvas)
        canvas.restore()

        if (note.text.isBlank()) continue
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseAndroidColor(box.color.ifBlank { NOTE_COLOR_BLACK })
            textSize = (box.font * height).toFloat().coerceAtLeast(8f)
            typeface = Typeface.DEFAULT
        }
        val layoutWidth = (boxWidth - pad * 2).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(note.text, 0, note.text.length, paint, layoutWidth)
            .setAlignment(
                when (box.align) {
                    "left" -> Layout.Alignment.ALIGN_NORMAL
                    "right" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                },
            )
            .setLineSpacing(0f, NOTE_LINE_HEIGHT_MULT)
            .setIncludePad(false)
            .build()
        val contentTop = top + titleBarH
        val availH = (boxHeight - titleBarH - pad).coerceAtLeast(1f)
        val dy = when (normalizeVAlign(box.valign)) {
            "middle" -> ((availH - layout.height) / 2f).coerceAtLeast(0f)
            "bottom" -> (availH - layout.height).coerceAtLeast(0f)
            else -> 0f
        }
        canvas.save()
        canvas.translate(left + pad, contentTop + dy)
        canvas.clipRect(0f, 0f, layoutWidth.toFloat(), availH)
        layout.draw(canvas)
        canvas.restore()
    }
}

private fun scale(source: Bitmap, width: Int, height: Int): Bitmap {
    if (source.width == width && source.height == height) return source
    return Bitmap.createScaledBitmap(source, width.coerceAtLeast(1), height.coerceAtLeast(1), true)
}

private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    bitmap.compress(format, quality.coerceIn(1, 100), out)
    return out.toByteArray()
}

private fun parseAndroidColor(hex: String): Int = try {
    android.graphics.Color.parseColor(hex)
} catch (_: Exception) {
    android.graphics.Color.parseColor(NOTE_COLOR_BLACK)
}
