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
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.WEBP_MAX_EDGE
import com.jianqiaofan.subtitleplayer.domain.screenshot.WEBP_QUALITY
import com.jianqiaofan.subtitleplayer.domain.screenshot.displayBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.fittedEdge
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
        if (note.text.isBlank()) continue
        val box = displayBox(note)
        val left = (box.x * width).toFloat()
        val top = (box.y * height).toFloat()
        val boxWidth = (box.width * width).toFloat().coerceAtLeast(1f)
        val boxHeight = (box.height * height).toFloat().coerceAtLeast(1f)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseAndroidColor(box.background)
            alpha = (box.opacity.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, background)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseAndroidColor(box.color.ifBlank { NOTE_COLOR_BLACK })
            textSize = (box.font * height).toFloat().coerceAtLeast(8f)
            typeface = Typeface.DEFAULT
        }
        val pad = height * 0.012f
        val layoutWidth = (boxWidth - pad * 2).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(note.text, 0, note.text.length, paint, layoutWidth)
            .setAlignment(
                when (box.align) {
                    "left" -> Layout.Alignment.ALIGN_NORMAL
                    "right" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                },
            )
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(left + pad, top + pad)
        canvas.clipRect(0f, 0f, layoutWidth.toFloat(), (boxHeight - pad * 2).coerceAtLeast(1f))
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
