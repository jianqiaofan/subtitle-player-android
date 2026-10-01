package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_BACKGROUND_COLORS
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_EDGE_HIT_PX
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_HANDLE_VISUAL_DP
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_PANEL_WIDTH_DP
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_TEXT_COLORS
import com.jianqiaofan.subtitleplayer.domain.screenshot.NoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.NoteResizeEdge
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.displayBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.hitNoteEdge
import com.jianqiaofan.subtitleplayer.domain.screenshot.isLightBackground
import com.jianqiaofan.subtitleplayer.domain.screenshot.moveNoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteNumberLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.notePanelTitle
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeAlign
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeFont
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeOpacity
import com.jianqiaofan.subtitleplayer.domain.screenshot.placeStylePanel
import com.jianqiaofan.subtitleplayer.domain.screenshot.resizeNoteBox
import kotlin.math.roundToInt

private val ActiveBorder = Color(0xFFE2C6FF)
private val PanelBg = Color(0xFF2B2B2B)
private val PanelFg = Color(0xFFEEEEEE)

@Composable
fun NoteOverlayLayer(
    notes: List<ScreenshotNote>,
    imageLeft: Float,
    imageTop: Float,
    imageWidth: Float,
    imageHeight: Float,
    activeNoteId: String?,
    showStylePanel: Boolean,
    onActivate: (String?) -> Unit,
    onShowStylePanel: (Boolean) -> Unit,
    onBoxChanged: (String, NoteBox, commit: Boolean) -> Unit,
    onCommitNote: (String) -> Unit = {},
    onNoteLongPress: (ScreenshotNote, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 1f || imageHeight <= 1f) return
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxSize()) {
        notes.forEachIndexed { index, note ->
            val box = displayBox(note, index, notes.size)
            val left = imageLeft + (box.x * imageWidth).toFloat()
            val top = imageTop + (box.y * imageHeight).toFloat()
            val width = (box.width * imageWidth).toFloat().coerceAtLeast(1f)
            val height = (box.height * imageHeight).toFloat().coerceAtLeast(1f)
            val active = note.id == activeNoteId
            val number = noteNumberLabel(notes, note.id)
            NoteFrame(
                text = note.text,
                numberLabel = number,
                box = box,
                left = left,
                top = top,
                width = width,
                height = height,
                imageHeight = imageHeight,
                active = active,
                onTap = {
                    onActivate(note.id)
                    onShowStylePanel(true)
                },
                onLongPress = { onNoteLongPress(note, Offset(left + width / 2f, top + height / 2f)) },
                onDrag = { edge, dx, dy ->
                    val ndx = (dx / imageWidth).toDouble()
                    val ndy = (dy / imageHeight).toDouble()
                    val next = when (edge) {
                        NoteResizeEdge.Move -> moveNoteBox(box, ndx, ndy)
                        NoteResizeEdge.Left -> resizeNoteBox(box, ndx, 0.0, fromLeft = true)
                        NoteResizeEdge.Right -> resizeNoteBox(box, ndx, 0.0)
                        NoteResizeEdge.Top -> resizeNoteBox(box, 0.0, ndy, fromTop = true)
                        NoteResizeEdge.Bottom -> resizeNoteBox(box, 0.0, ndy)
                        NoteResizeEdge.TopLeft -> resizeNoteBox(box, ndx, ndy, fromLeft = true, fromTop = true)
                        NoteResizeEdge.TopRight -> resizeNoteBox(box, ndx, ndy, fromTop = true)
                        NoteResizeEdge.BottomLeft -> resizeNoteBox(box, ndx, ndy, fromLeft = true)
                        NoteResizeEdge.BottomRight -> resizeNoteBox(box, ndx, ndy)
                    }
                    onBoxChanged(note.id, next, false)
                },
                onDragEnd = { onCommitNote(note.id) },
            )
            if (active && showStylePanel) {
                val panelWidthPx = with(density) { NOTE_PANEL_WIDTH_DP.dp.toPx() }
                val panelHeightPx = with(density) { 220.dp.toPx() }
                val placed = placeStylePanel(
                    left, top, width, height,
                    panelWidthPx, panelHeightPx,
                    imageLeft, imageTop, imageWidth, imageHeight,
                )
                NoteStylePanel(
                    title = notePanelTitle(note.text),
                    box = box,
                    modifier = Modifier
                        .offset { IntOffset(placed.left.roundToInt(), placed.top.roundToInt()) }
                        .width(NOTE_PANEL_WIDTH_DP.dp),
                    onClose = { onShowStylePanel(false) },
                    onChange = { next, commit -> onBoxChanged(note.id, next, commit) },
                )
            }
        }
    }
}

@Composable
private fun NoteFrame(
    text: String,
    numberLabel: String?,
    box: NoteBox,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    imageHeight: Float,
    active: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (NoteResizeEdge, Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val bg = parseHex(box.background).copy(alpha = box.opacity.toFloat().coerceIn(0.15f, 1f))
    val fg = parseHex(box.color)
    val border = when {
        active -> ActiveBorder
        isLightBackground(box.background) -> Color(0xFF333333)
        else -> Color.White.copy(alpha = 0.85f)
    }
    val fontSp = ((box.font * imageHeight).toFloat() / LocalDensity.current.density).coerceAtLeast(8f)
    var edge by remember { mutableStateOf(NoteResizeEdge.Move) }
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(LocalDensity.current) { width.toDp() },
                height = with(LocalDensity.current) { height.toDp() },
            )
            .background(bg, RoundedCornerShape(4.dp))
            .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .pointerInput(active) {
                detectDragGestures(
                    onDragStart = { offset ->
                        edge = hitNoteEdge(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), NOTE_EDGE_HIT_PX)
                        onTap()
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(edge, amount.x, amount.y)
                    },
                )
            }
            .padding(6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (numberLabel != null) {
                Text(
                    text = numberLabel,
                    color = fg.copy(alpha = 0.85f),
                    fontSize = (fontSp * 0.7f).sp,
                    maxLines = 1,
                )
            }
            Text(
                text = text,
                color = fg,
                fontSize = fontSp.sp,
                textAlign = when (box.align) {
                    "left" -> TextAlign.Start
                    "right" -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.fillMaxSize(),
                overflow = TextOverflow.Clip,
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(NOTE_HANDLE_VISUAL_DP.dp)
                    .background(ActiveBorder.copy(alpha = 0.85f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun NoteStylePanel(
    title: String,
    box: NoteBox,
    onClose: () -> Unit,
    onChange: (NoteBox, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PanelBg, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE2C6FF).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = PanelFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
            )
            TextButton(onClick = onClose) { Text("X") }
        }
        Text("透明度", color = PanelFg, fontSize = 12.sp)
        Slider(
            value = box.opacity.toFloat(),
            onValueChange = { onChange(box.copy(opacity = normalizeOpacity(it.toDouble())), false) },
            onValueChangeFinished = { onChange(box, true) },
            valueRange = 0.15f..1f,
        )
        Text("字号", color = PanelFg, fontSize = 12.sp)
        Slider(
            value = box.font.toFloat(),
            onValueChange = { onChange(box.copy(font = normalizeFont(it.toDouble())), false) },
            onValueChangeFinished = { onChange(box, true) },
            valueRange = 0.02f..0.16f,
        )
        Text("背景", color = PanelFg, fontSize = 12.sp)
        ColorRow(NOTE_BACKGROUND_COLORS, box.background) { hex ->
            onChange(box.copy(background = hex), true)
        }
        Text("文字", color = PanelFg, fontSize = 12.sp)
        ColorRow(NOTE_TEXT_COLORS, box.color) { hex ->
            onChange(box.copy(color = hex), true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("left" to "左", "center" to "中", "right" to "右").forEach { (align, label) ->
                val selected = normalizeAlign(box.align) == align
                TextButton(onClick = { onChange(box.copy(align = align), true) }) {
                    Text(label, color = if (selected) Color(0xFFFFE14A) else PanelFg)
                }
            }
        }
    }
}

@Composable
private fun ColorRow(colors: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { hex ->
            val chosen = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(22.dp)
                    .background(parseHex(hex), RoundedCornerShape(4.dp))
                    .border(
                        if (chosen) 2.dp else 1.dp,
                        if (chosen) Color(0xFFFFE14A) else Color.White.copy(alpha = 0.35f),
                        RoundedCornerShape(4.dp),
                    )
                    .pointerInput(hex) {
                        detectTapGestures { onPick(hex) }
                    },
            )
        }
    }
}

private fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.White
}
