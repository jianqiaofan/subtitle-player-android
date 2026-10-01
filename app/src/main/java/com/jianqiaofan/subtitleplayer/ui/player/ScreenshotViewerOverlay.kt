package com.jianqiaofan.subtitleplayer.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.CreateJpegDocument
import com.jianqiaofan.subtitleplayer.data.ManagedScreenshot
import com.jianqiaofan.subtitleplayer.data.renderAnnotatedJpeg
import com.jianqiaofan.subtitleplayer.domain.screenshot.NoteBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.TIP_ID_SCREENSHOT_EXPORT
import com.jianqiaofan.subtitleplayer.domain.screenshot.VIEWER_MODE_HINT
import com.jianqiaofan.subtitleplayer.domain.screenshot.averageLuminance01
import com.jianqiaofan.subtitleplayer.domain.screenshot.displayBox
import com.jianqiaofan.subtitleplayer.domain.screenshot.fittedImageRect
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteNumberLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.otherNumberedNotes
import com.jianqiaofan.subtitleplayer.domain.screenshot.reuseNoteStyle
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotExportFileName
import com.jianqiaofan.subtitleplayer.domain.screenshot.touchShotUpdated
import com.jianqiaofan.subtitleplayer.domain.screenshot.viewerControlsOnLight
import com.jianqiaofan.subtitleplayer.ui.common.TipDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One item in the viewer carousel (current video or manage list). */
data class ViewerItem(
    val shot: ScreenshotShot,
    /** When set, edits write back to this managed location (may be another video). */
    val managed: ManagedScreenshot? = null,
)

@Composable
fun ScreenshotViewerOverlay(
    items: List<ViewerItem>,
    initialIndex: Int,
    loadImage: suspend (ViewerItem) -> ByteArray?,
    onSaveShot: (ViewerItem, ScreenshotShot) -> Unit,
    onEditShot: (ViewerItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex)) }
    var shot by remember(items, index) { mutableStateOf(items[index].shot) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var showStylePanel by remember { mutableStateOf(false) }
    var showModeHint by remember { mutableStateOf(true) }
    var blankMenu by remember { mutableStateOf(false) }
    var blankMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var noteMenu by remember { mutableStateOf<ScreenshotNote?>(null) }
    var noteMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var editingNote by remember { mutableStateOf<ScreenshotNote?>(null) }
    var showExportTip by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf(false) }
    var lightControls by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }

    LaunchedEffect(Unit) {
        delay(3_000)
        showModeHint = false
    }

    LaunchedEffect(index, items) {
        val item = items.getOrNull(index) ?: return@LaunchedEffect
        shot = item.shot
        activeNoteId = null
        showStylePanel = false
        bitmap?.recycle()
        bitmap = null
        val bytes = loadImage(item)
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        bitmap = decoded
        lightControls = decoded?.let { sampleCornerLuminance(it) }?.let { viewerControlsOnLight(it) } == true
    }

    fun persist(next: ScreenshotShot) {
        shot = next
        onSaveShot(items[index], next)
    }

    fun updateNoteBox(noteId: String, box: NoteBox, commit: Boolean) {
        val now = System.currentTimeMillis()
        val notes = shot.notes.map { note ->
            if (note.id != noteId) note
            else note.copy(box = box, updatedAt = if (commit) now else note.updatedAt)
        }
        val next = if (commit) touchShotUpdated(shot, notes, now) else shot.copy(notes = notes)
        shot = next
        if (commit) onSaveShot(items[index], next)
    }

    fun commitNote(noteId: String) {
        val note = shot.notes.find { it.id == noteId } ?: return
        val box = note.box ?: displayBox(note)
        updateNoteBox(noteId, box, commit = true)
    }

    val exportLauncher = rememberLauncherForActivityResult(CreateJpegDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val source = bitmap ?: return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                renderAnnotatedJpeg(source, shot.notes, showNotes = true)
            }
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                }
                prefs.rememberScreenshotExportUri(uri.toString())
            }
        }
    }

    fun startExport() {
        scope.launch {
            if (prefs.isTipDismissed(TIP_ID_SCREENSHOT_EXPORT)) {
                val initial = prefs.screenshotExportUriOnce()?.let { Uri.parse(it) }
                exportLauncher.launch(
                    CreateJpegDocument.Args(screenshotExportFileName(shot.title), initial),
                )
            } else {
                pendingExport = true
                showExportTip = true
            }
        }
    }

    BackHandler { onClose() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(index, items.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragAccum > 80f && index > 0 -> index -= 1
                            dragAccum < -80f && index < items.lastIndex -> index += 1
                        }
                        dragAccum = 0f
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragAccum += amount
                    },
                )
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()
            val image = bitmap
            val fitted = if (image != null) {
                fittedImageRect(containerW, containerH, image.width.toFloat(), image.height.toFloat())
            } else {
                fittedImageRect(containerW, containerH, containerW, containerH)
            }
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = shot.title,
                    modifier = Modifier
                        .offset { IntOffset(fitted.left.roundToInt(), fitted.top.roundToInt()) }
                        .size(
                            width = with(density) { fitted.width.toDp() },
                            height = with(density) { fitted.height.toDp() },
                        ),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                activeNoteId = null
                                showStylePanel = false
                            },
                            onLongPress = { offset ->
                                blankMenuOffset = offset
                                blankMenu = true
                            },
                        )
                    },
            )
            NoteOverlayLayer(
                notes = shot.notes,
                imageLeft = fitted.left,
                imageTop = fitted.top,
                imageWidth = fitted.width,
                imageHeight = fitted.height,
                activeNoteId = activeNoteId,
                showStylePanel = showStylePanel,
                onActivate = { activeNoteId = it },
                onShowStylePanel = { showStylePanel = it },
                onBoxChanged = ::updateNoteBox,
                onCommitNote = ::commitNote,
                onNoteLongPress = { note, offset ->
                    noteMenu = note
                    noteMenuOffset = offset
                },
            )
        }

        ViewerChromeButton(
            onLight = lightControls,
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ViewerChromeButton(
                onLight = lightControls,
                enabled = index > 0,
                onClick = { if (index > 0) index -= 1 },
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一图")
            }
            ViewerChromeButton(
                onLight = lightControls,
                enabled = index < items.lastIndex,
                onClick = { if (index < items.lastIndex) index += 1 },
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一图")
            }
        }

        AnimatedVisibility(
            visible = showModeHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = VIEWER_MODE_HINT,
                color = Color(0xFFEDE7F6),
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color(0xCC3A2458), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFFFFE14A).copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        DropdownMenu(
            expanded = blankMenu,
            onDismissRequest = { blankMenu = false },
            offset = with(LocalDensity.current) {
                androidx.compose.ui.unit.DpOffset(blankMenuOffset.x.toDp(), blankMenuOffset.y.toDp())
            },
        ) {
            DropdownMenuItem(
                text = { Text("编辑截图") },
                onClick = {
                    blankMenu = false
                    onEditShot(items[index].copy(shot = shot))
                },
            )
            DropdownMenuItem(
                text = { Text("截屏保存") },
                onClick = {
                    blankMenu = false
                    startExport()
                },
            )
        }

        val menuNote = noteMenu
        if (menuNote != null) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { noteMenu = null },
                offset = with(LocalDensity.current) {
                    androidx.compose.ui.unit.DpOffset(noteMenuOffset.x.toDp(), noteMenuOffset.y.toDp())
                },
            ) {
                DropdownMenuItem(
                    text = { Text("编辑笔记") },
                    onClick = {
                        editingNote = menuNote
                        noteMenu = null
                    },
                )
                otherNumberedNotes(shot.notes, menuNote.id).forEach { (label, source) ->
                    DropdownMenuItem(
                        text = { Text("复用 $label 样式") },
                        onClick = {
                            val now = System.currentTimeMillis()
                            val updated = reuseNoteStyle(menuNote, source, now)
                            val notes = shot.notes.map { if (it.id == updated.id) updated else it }
                            persist(touchShotUpdated(shot, notes, now))
                            noteMenu = null
                        },
                    )
                }
                val deleteLabel = noteNumberLabel(shot.notes, menuNote.id)?.let { "删除 $it 笔记" } ?: "删除笔记"
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        val now = System.currentTimeMillis()
                        val notes = shot.notes.filterNot { it.id == menuNote.id }
                        persist(touchShotUpdated(shot, notes, now))
                        if (activeNoteId == menuNote.id) {
                            activeNoteId = null
                            showStylePanel = false
                        }
                        noteMenu = null
                    },
                )
            }
        }
    }

    if (showExportTip) {
        TipDialog(
            message = "此功能是将当前笔记和图片截屏保存为一张普通图片。",
            onKnown = { dontRemind ->
                scope.launch {
                    if (dontRemind) prefs.dismissTip(TIP_ID_SCREENSHOT_EXPORT)
                    showExportTip = false
                    if (pendingExport) {
                        pendingExport = false
                        val initial = prefs.screenshotExportUriOnce()?.let { Uri.parse(it) }
                        exportLauncher.launch(
                            CreateJpegDocument.Args(screenshotExportFileName(shot.title), initial),
                        )
                    }
                }
            },
            onDismiss = {
                showExportTip = false
                pendingExport = false
            },
        )
    }

    val noteToEdit = editingNote
    if (noteToEdit != null) {
        var draft by remember(noteToEdit.id) { mutableStateOf(noteToEdit.text) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("编辑笔记") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    val notes = shot.notes.map {
                        if (it.id == noteToEdit.id) it.copy(text = draft, updatedAt = now) else it
                    }
                    persist(touchShotUpdated(shot, notes, now))
                    editingNote = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ViewerChromeButton(
    onLight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val bg = if (onLight) Color(0xCC1A1A1A) else Color(0xCCF5F0FF)
    val fg = if (onLight) Color(0xFFFFE14A) else Color(0xFF3A2458)
    val border = Color(0xFFFFE14A)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border.copy(alpha = if (enabled) 0.9f else 0.35f), RoundedCornerShape(10.dp)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides fg.copy(alpha = if (enabled) 1f else 0.35f),
            ) { content() }
        }
    }
}

private fun sampleCornerLuminance(bitmap: Bitmap): Double {
    val w = bitmap.width.coerceAtLeast(1)
    val h = bitmap.height.coerceAtLeast(1)
    val x = (w * 0.88f).roundToInt().coerceIn(0, w - 1)
    val y = (h * 0.45f).roundToInt().coerceIn(0, h - 1)
    val sample = bitmap.getPixel(x, y)
    val r = (sample shr 16) and 0xFF
    val g = (sample shr 8) and 0xFF
    val b = sample and 0xFF
    return averageLuminance01(r, g, b)
}
