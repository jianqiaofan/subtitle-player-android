package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.domain.screenshot.FittedRect
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_BACKGROUND_COLORS
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_EDGE_HIT_PX
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_HANDLE_VISUAL_DP
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_LINE_HEIGHT_MULT
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_PANEL_HEIGHT_DP
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_PANEL_WIDTH_DP
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_STYLE_PRESETS
import com.jianqiaofan.subtitleplayer.domain.screenshot.NOTE_TEXT_COLORS
import com.jianqiaofan.subtitleplayer.domain.screenshot.NoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.NoteResizeEdge
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.applyNoteStylePreset
import com.jianqiaofan.subtitleplayer.domain.screenshot.displayBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.hitNoteEdge
import com.jianqiaofan.subtitleplayer.domain.screenshot.isLightBackground
import com.jianqiaofan.subtitleplayer.domain.screenshot.moveNoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteNumberLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeAlign
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeFont
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeOpacity
import com.jianqiaofan.subtitleplayer.domain.screenshot.normalizeVAlign
import com.jianqiaofan.subtitleplayer.domain.screenshot.resizeNoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.shiftInside
import com.jianqiaofan.subtitleplayer.domain.screenshot.stylePanelNoteTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val ActiveBorder = Color(0xFFE2C6FF)
private val PanelBg = Color(0xFF2B2B2B)
private val PanelFg = Color(0xFFEEEEEE)

@Composable
fun NoteOverlayLayer(
    notes: List<ScreenshotNote>,
    shotId: String,
    imageLeft: Float,
    imageTop: Float,
    imageWidth: Float,
    imageHeight: Float,
    activeNoteId: String?,
    showStylePanel: Boolean,
    onActivate: (String?) -> Unit,
    onShowStylePanel: (Boolean) -> Unit,
    onBoxChanged: (String, NoteBox, commit: Boolean) -> Unit,
    onNoteLongPress: (ScreenshotNote, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 1f || imageHeight <= 1f) return
    val density = LocalDensity.current
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val panelWidthPx = with(density) { NOTE_PANEL_WIDTH_DP.dp.toPx() }
    val panelHeightPx = with(density) { NOTE_PANEL_HEIGHT_DP.dp.toPx() }

    /** Absolute top-left of the style panel inside the viewer; null until loaded/placed. */
    var panelPos by remember(shotId) { mutableStateOf<Offset?>(null) }
    var panelPosReady by remember(shotId) { mutableStateOf(false) }

    LaunchedEffect(shotId, imageLeft, imageTop, imageWidth, imageHeight, panelWidthPx, panelHeightPx) {
        val saved = prefs.noteStylePanelPosOnce(shotId)
        val placed = if (saved != null) {
            FittedRect(
                imageLeft + saved.first * imageWidth,
                imageTop + saved.second * imageHeight,
                panelWidthPx,
                panelHeightPx,
            )
        } else {
            FittedRect(
                imageLeft + (imageWidth - panelWidthPx) / 2f,
                imageTop + (imageHeight - panelHeightPx) / 2f,
                panelWidthPx,
                panelHeightPx,
            )
        }
        val clamped = shiftInside(placed, imageLeft, imageTop, imageWidth, imageHeight)
        panelPos = Offset(clamped.left, clamped.top)
        panelPosReady = true
    }

    fun persistPanelPos(left: Float, top: Float) {
        if (imageWidth <= 1f || imageHeight <= 1f) return
        val xRel = ((left - imageLeft) / imageWidth).coerceIn(0f, 1f)
        val yRel = ((top - imageTop) / imageHeight).coerceIn(0f, 1f)
        scope.launch { prefs.rememberNoteStylePanelPos(shotId, xRel, yRel) }
    }

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
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                active = active,
                onActivate = { onActivate(note.id) },
                onClick = {
                    onActivate(note.id)
                    onShowStylePanel(true)
                },
                onLongPress = { onNoteLongPress(note, Offset(left + width / 2f, top + height / 2f)) },
                onBoxDrag = { next, commit -> onBoxChanged(note.id, next, commit) },
            )
        }

        val panelNoteId = activeNoteId
        if (showStylePanel && panelNoteId != null && panelPosReady) {
            val note = notes.find { it.id == panelNoteId }
            if (note != null) {
                val box = displayBox(note, notes.indexOf(note).coerceAtLeast(0), notes.size)
                val origin = panelPos ?: Offset(
                    imageLeft + (imageWidth - panelWidthPx) / 2f,
                    imageTop + (imageHeight - panelHeightPx) / 2f,
                )
                val clamped = shiftInside(
                    FittedRect(origin.x, origin.y, panelWidthPx, panelHeightPx),
                    imageLeft, imageTop, imageWidth, imageHeight,
                )
                NoteStylePanel(
                    title = stylePanelNoteTitle(notes, note.id),
                    box = box,
                    modifier = Modifier
                        .offset { IntOffset(clamped.left.roundToInt(), clamped.top.roundToInt()) }
                        .width(NOTE_PANEL_WIDTH_DP.dp),
                    onClose = { onShowStylePanel(false) },
                    onChange = { next, commit -> onBoxChanged(note.id, next, commit) },
                    onHeaderDrag = { dx, dy ->
                        val next = shiftInside(
                            FittedRect(
                                clamped.left + dx,
                                clamped.top + dy,
                                panelWidthPx,
                                panelHeightPx,
                            ),
                            imageLeft, imageTop, imageWidth, imageHeight,
                        )
                        panelPos = Offset(next.left, next.top)
                        persistPanelPos(next.left, next.top)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoteFrame(
    text: String,
    numberLabel: String,
    box: NoteBox,
    left: Float,
    top: Float,
    imageWidth: Float,
    imageHeight: Float,
    active: Boolean,
    onActivate: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBoxDrag: (NoteBox, commit: Boolean) -> Unit,
) {
    val bg = parseHex(box.background).copy(alpha = box.opacity.toFloat().coerceIn(0.15f, 1f))
    val fg = parseHex(box.color)
    val titleBgHex = box.titleBackground.trim()
    val titleBarBg = if (titleBgHex.isNotEmpty()) {
        parseHex(titleBgHex).copy(alpha = box.opacity.toFloat().coerceIn(0.15f, 1f))
    } else {
        Color.Transparent
    }
    val border = when {
        active -> ActiveBorder
        isLightBackground(box.background) -> Color(0xFF333333)
        else -> Color.White.copy(alpha = 0.85f)
    }
    val fontSp = ((box.font * imageHeight).toFloat() / LocalDensity.current.density).coerceAtLeast(8f)
    val titleFontSp = (fontSp * 0.72f).coerceAtLeast(8f)
    val lineSp = (fontSp * NOTE_LINE_HEIGHT_MULT).sp
    val latestBox = rememberUpdatedState(box)
    val latestOnActivate = rememberUpdatedState(onActivate)
    val latestOnClick = rememberUpdatedState(onClick)
    val latestOnLongPress = rememberUpdatedState(onLongPress)
    val latestOnBoxDrag = rememberUpdatedState(onBoxDrag)
    val viewConfiguration = LocalViewConfiguration.current
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    var liveBox by remember { mutableStateOf<NoteBox?>(null) }
    val shown = liveBox ?: box
    val drawLeft = left + ((shown.x - box.x) * imageWidth).toFloat()
    val drawTop = top + ((shown.y - box.y) * imageHeight).toFloat()
    val drawWidth = (shown.width * imageWidth).toFloat().coerceAtLeast(1f)
    val drawHeight = (shown.height * imageHeight).toFloat().coerceAtLeast(1f)
    val align = when (shown.align) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        else -> TextAlign.Center
    }
    val contentAlignment = when (normalizeVAlign(shown.valign)) {
        "middle" -> Alignment.Center
        "bottom" -> Alignment.BottomCenter
        else -> Alignment.TopCenter
    }
    val corner = RoundedCornerShape(4.dp)
    val titleCorner = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(drawLeft.roundToInt(), drawTop.roundToInt()) }
            .size(
                width = with(LocalDensity.current) { drawWidth.toDp() },
                height = with(LocalDensity.current) { drawHeight.toDp() },
            )
            .background(bg, corner)
            .border(if (active) 2.dp else 1.dp, border, corner)
            .pointerInput(imageWidth, imageHeight, longPressTimeout) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    latestOnActivate.value()
                    val edge = hitNoteEdge(
                        down.position.x,
                        down.position.y,
                        size.width.toFloat(),
                        size.height.toFloat(),
                        NOTE_EDGE_HIT_PX,
                    )
                    val slopChange = withTimeoutOrNull(longPressTimeout) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                    }
                    if (slopChange != null) {
                        var current = latestBox.value
                        liveBox = current
                        drag(slopChange.id) { change ->
                            val amount = change.positionChange()
                            change.consume()
                            val ndx = (amount.x / imageWidth).toDouble()
                            val ndy = (amount.y / imageHeight).toDouble()
                            current = applyNoteDrag(current, edge, ndx, ndy)
                            liveBox = current
                            latestOnBoxDrag.value(current, false)
                        }
                        liveBox = null
                        latestOnBoxDrag.value(current, true)
                        return@awaitEachGesture
                    }
                    val stillDown = currentEvent.changes.any { it.id == down.id && it.pressed }
                    if (stillDown) {
                        latestOnLongPress.value()
                        waitForUpOrCancellation()
                    } else {
                        latestOnClick.value()
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(titleBarBg, titleCorner)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = numberLabel,
                    color = fg.copy(alpha = 0.92f),
                    fontSize = titleFontSp.sp,
                    lineHeight = (titleFontSp * NOTE_LINE_HEIGHT_MULT).sp,
                    fontWeight = if (shown.titleBold) FontWeight.Bold else FontWeight.Medium,
                    fontStyle = if (shown.titleItalic) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = contentAlignment,
            ) {
                Text(
                    text = text,
                    color = fg,
                    fontSize = fontSp.sp,
                    lineHeight = lineSp,
                    textAlign = align,
                    modifier = Modifier.fillMaxWidth(),
                    overflow = TextOverflow.Clip,
                )
            }
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

private fun applyNoteDrag(box: NoteBox, edge: NoteResizeEdge, ndx: Double, ndy: Double): NoteBox =
    when (edge) {
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

@Composable
private fun NoteStylePanel(
    title: String,
    box: NoteBox,
    onClose: () -> Unit,
    onChange: (NoteBox, Boolean) -> Unit,
    onHeaderDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnHeaderDrag = rememberUpdatedState(onHeaderDrag)
    Column(
        modifier = modifier
            .background(PanelBg, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE2C6FF).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                title,
                color = PanelFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            latestOnHeaderDrag.value(dragAmount.x, dragAmount.y)
                        }
                    },
                fontSize = 14.sp,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = PanelFg,
                )
            }
        }
        Text("预设样式", color = PanelFg, fontSize = 12.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NOTE_STYLE_PRESETS.forEach { preset ->
                val bg = parseHex(preset.background)
                val fg = parseHex(preset.color)
                val titleBg = parseHex(preset.titleBackground)
                val chipShape = RoundedCornerShape(8.dp)
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .height(52.dp)
                        .background(bg.copy(alpha = preset.opacity.toFloat()), chipShape)
                        .border(1.dp, Color.White.copy(alpha = 0.35f), chipShape)
                        .pointerInput(preset.id) {
                            detectTapGestures {
                                onChange(applyNoteStylePreset(box, preset), true)
                            }
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                titleBg.copy(alpha = preset.opacity.toFloat()),
                                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "Note",
                            color = fg,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                        )
                    }
                    Text(
                        preset.title,
                        color = fg,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
        StyleLabeledRow("透明度") {
            Slider(
                value = box.opacity.toFloat(),
                onValueChange = { onChange(box.copy(opacity = normalizeOpacity(it.toDouble())), false) },
                onValueChangeFinished = { onChange(box, true) },
                valueRange = 0.15f..1f,
                modifier = Modifier.weight(1f),
            )
        }
        StyleLabeledRow("字号") {
            Slider(
                value = box.font.toFloat(),
                onValueChange = { onChange(box.copy(font = normalizeFont(it.toDouble())), false) },
                onValueChangeFinished = { onChange(box, true) },
                valueRange = 0.02f..0.16f,
                modifier = Modifier.weight(1f),
            )
        }
        StyleLabeledRow("背景") {
            ColorRow(NOTE_BACKGROUND_COLORS, box.background) { hex ->
                onChange(box.copy(background = hex), true)
            }
        }
        StyleLabeledRow("文字") {
            ColorRow(NOTE_TEXT_COLORS, box.color) { hex ->
                onChange(box.copy(color = hex), true)
            }
        }
        StyleLabeledRow("水平") {
            listOf("left" to "左", "center" to "中", "right" to "右").forEach { (align, label) ->
                val selected = normalizeAlign(box.align) == align
                TextButton(onClick = { onChange(box.copy(align = align), true) }) {
                    Text(label, color = if (selected) Color(0xFFFFE14A) else PanelFg)
                }
            }
        }
        StyleLabeledRow("垂直") {
            listOf("top" to "上", "middle" to "中", "bottom" to "下").forEach { (valign, label) ->
                val selected = normalizeVAlign(box.valign) == valign
                TextButton(onClick = { onChange(box.copy(valign = valign), true) }) {
                    Text(label, color = if (selected) Color(0xFFFFE14A) else PanelFg)
                }
            }
        }
    }
}

@Composable
private fun StyleLabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = PanelFg,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
        )
        content()
    }
}

@Composable
private fun ColorRow(colors: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
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
