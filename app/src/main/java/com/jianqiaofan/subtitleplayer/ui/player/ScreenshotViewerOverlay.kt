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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.jianqiaofan.subtitleplayer.domain.screenshot.fittedImageRect
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteNumberLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.otherNumberedNotes
import com.jianqiaofan.subtitleplayer.domain.screenshot.reuseNoteStyle
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotContentSame
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
    onEditShot: (ViewerItem) -> Unit,
    /** Remove the overlay from the composition (close / back). */
    onDismissRequest: () -> Unit,
    /** Fired once when the overlay leaves; [dirtyItems] should be written then synced. */
    onSessionEnd: (dirtyItems: List<ViewerItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val baseline = remember(items.map { it.shot.id }) { items.associate { it.shot.id to it.shot } }
    var sessionItems by remember { mutableStateOf(items) }
    var index by remember { mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex)) }
    if (index > sessionItems.lastIndex) index = sessionItems.lastIndex
    var shot by remember { mutableStateOf(sessionItems[index.coerceIn(0, sessionItems.lastIndex)].shot) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var showStylePanel by remember { mutableStateOf(false) }
    var showModeHint by remember { mutableStateOf(true) }
    var blankMenu by remember { mutableStateOf(false) }
    var blankMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var noteMenu by remember { mutableStateOf<ScreenshotNote?>(null) }
    var noteMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingDeleteNote by remember { mutableStateOf<ScreenshotNote?>(null) }
    var dialogEditNote by remember { mutableStateOf<ScreenshotNote?>(null) }
    var showExportTip by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf(false) }
    var lightControls by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }

    fun cacheShot(next: ScreenshotShot) {
        shot = next
        sessionItems = sessionItems.map { entry ->
            if (entry.shot.id != next.id) entry
            else entry.copy(shot = next, managed = entry.managed?.copy(shot = next))
        }
    }

    fun dirtyItems(): List<ViewerItem> =
        sessionItems.filter { item ->
            val original = baseline[item.shot.id]
            original == null || !screenshotContentSame(original, item.shot)
        }

    val sessionEnded = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val latestSessionEnd = rememberUpdatedState(onSessionEnd)
    val latestSessionItems = rememberUpdatedState(sessionItems)
    val latestBaseline = rememberUpdatedState(baseline)

    fun endSession(dirty: List<ViewerItem>) {
        if (!sessionEnded.compareAndSet(false, true)) return
        latestSessionEnd.value(dirty)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!sessionEnded.compareAndSet(false, true)) return@onDispose
            val items = latestSessionItems.value
            val base = latestBaseline.value
            val dirty = items.filter { item ->
                val original = base[item.shot.id]
                original == null || !screenshotContentSame(original, item.shot)
            }
            latestSessionEnd.value(dirty)
        }
    }

    LaunchedEffect(Unit) {
        delay(3_000)
        showModeHint = false
    }

    // Adopt parent-supplied shot updates (e.g. full-screen edit dialog) into the session cache.
    LaunchedEffect(items) {
        val byId = items.associateBy { it.shot.id }
        sessionItems = sessionItems.map { local ->
            val incoming = byId[local.shot.id] ?: return@map local
            if (incoming.shot != local.shot) incoming else local
        }
        sessionItems.getOrNull(index)?.let { shot = it.shot }
    }

    val currentShotId = sessionItems.getOrNull(index)?.shot?.id
    LaunchedEffect(index, currentShotId) {
        val item = sessionItems.getOrNull(index) ?: return@LaunchedEffect
        shot = item.shot
        activeNoteId = null
        showStylePanel = false
        val previous = bitmap
        val bytes = loadImage(item)
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        bitmap = decoded
        previous?.recycle()
        lightControls = decoded?.let { sampleCornerLuminance(it) }?.let { viewerControlsOnLight(it) } == true
    }

    fun updateNoteBox(noteId: String, box: NoteBox, commit: Boolean) {
        val now = System.currentTimeMillis()
        val notes = shot.notes.map { note ->
            if (note.id != noteId) note
            else note.copy(box = box, updatedAt = if (commit) now else note.updatedAt)
        }
        val next = if (commit) touchShotUpdated(shot, notes, now) else shot.copy(notes = notes)
        if (commit) cacheShot(next) else shot = next
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

    fun dismiss() {
        endSession(dirtyItems())
        onDismissRequest()
    }

    BackHandler { dismiss() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(index, sessionItems.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragAccum > 80f && index > 0 -> index -= 1
                            dragAccum < -80f && index < sessionItems.lastIndex -> index += 1
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
                shotId = shot.id,
                imageLeft = fitted.left,
                imageTop = fitted.top,
                imageWidth = fitted.width,
                imageHeight = fitted.height,
                activeNoteId = activeNoteId,
                showStylePanel = showStylePanel,
                onActivate = { activeNoteId = it },
                onShowStylePanel = { showStylePanel = it },
                onBoxChanged = ::updateNoteBox,
                onNoteLongPress = { note, offset ->
                    noteMenu = note
                    noteMenuOffset = offset
                },
            )
        }

        ViewerChromeButton(
            onLight = lightControls,
            onClick = { dismiss() },
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
                onClick = {
                    if (index > 0) index -= 1
                },
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一图")
            }
            Text(
                text = "${index + 1}/${sessionItems.size}",
                color = if (lightControls) Color(0xFF1A1A1A) else Color(0xFFEDE7F6),
                fontSize = 13.sp,
            )
            ViewerChromeButton(
                onLight = lightControls,
                enabled = index < sessionItems.lastIndex,
                onClick = {
                    if (index < sessionItems.lastIndex) index += 1
                },
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
                    onEditShot(sessionItems[index].copy(shot = shot))
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
                        dialogEditNote = menuNote
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
                            cacheShot(touchShotUpdated(shot, notes, now))
                            noteMenu = null
                        },
                    )
                }
                val deleteLabel = "删除 ${noteNumberLabel(shot.notes, menuNote.id)} 笔记"
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        pendingDeleteNote = menuNote
                        noteMenu = null
                    },
                )
            }
        }
    }

    val notePendingDelete = pendingDeleteNote
    if (notePendingDelete != null) {
        val label = noteNumberLabel(shot.notes, notePendingDelete.id)
        AlertDialog(
            onDismissRequest = { pendingDeleteNote = null },
            title = { Text("确认删除") },
            text = { Text("确定删除 $label 笔记？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    val notes = shot.notes.filterNot { it.id == notePendingDelete.id }
                    cacheShot(touchShotUpdated(shot, notes, now))
                    if (activeNoteId == notePendingDelete.id) {
                        activeNoteId = null
                        showStylePanel = false
                    }
                    pendingDeleteNote = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteNote = null }) { Text("取消") }
            },
        )
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

    val noteToEdit = dialogEditNote
    if (noteToEdit != null) {
        var draft by remember(noteToEdit.id) { mutableStateOf(noteToEdit.text) }
        AlertDialog(
            onDismissRequest = { dialogEditNote = null },
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
                    cacheShot(touchShotUpdated(shot, notes, now))
                    dialogEditNote = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { dialogEditNote = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun ViewerChromeButton(
    onLight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val bg = if (onLight) Color(0xCC1A1A1A) else Color(0xCCEDE7F6)
    val fg = if (onLight) Color(0xFFFFE14A) else Color(0xFF4A148C)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFFE14A).copy(alpha = if (onLight) 0.9f else 0.55f), RoundedCornerShape(8.dp)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) fg else fg.copy(alpha = 0.35f),
        ) { content() }
    }
}

private fun sampleCornerLuminance(bitmap: Bitmap): Double {
    val w = bitmap.width.coerceAtLeast(1)
    val h = bitmap.height.coerceAtLeast(1)
    val samples = listOf(
        0 to 0,
        w - 1 to 0,
        0 to h - 1,
        w - 1 to h - 1,
        w / 2 to 0,
        w / 2 to h - 1,
    )
    var sum = 0.0
    for ((x, y) in samples) {
        val c = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
        sum += averageLuminance01(
            android.graphics.Color.red(c),
            android.graphics.Color.green(c),
            android.graphics.Color.blue(c),
        )
    }
    return sum / samples.size
}
