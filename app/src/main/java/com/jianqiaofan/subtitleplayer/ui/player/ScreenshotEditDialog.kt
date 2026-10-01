package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.appendJoinedCueTexts
import com.jianqiaofan.subtitleplayer.domain.screenshot.appendScreenshotTitleTags
import com.jianqiaofan.subtitleplayer.domain.screenshot.joinCueTextsForNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.nearbyCuesAroundTime
import com.jianqiaofan.subtitleplayer.domain.screenshot.newScreenshotId
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteChipTimeLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.noteTagLabel
import com.jianqiaofan.subtitleplayer.domain.screenshot.titleHasKnownTag
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import com.jianqiaofan.subtitleplayer.domain.tags.PRESET_TAGS
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val CenterCueBg = Color(0xFF7A4EB5)
private val CheckYellow = Color(0xFFFFE14A)
private val NoteChipIdleBg = Color(0xFF3A2458)
private val NoteChipActiveBg = AccentPurple
private val NoteChipIdleBorder = Color.White
private val NoteChipActiveBorder = CheckYellow
/** Below this panel content width, cue list and notes stack vertically. */
private val SideBySideMinWidth = 600.dp

/** Fixed notes pane height in stacked (narrow) layout so extra dialog height goes to cues. */
private val StackedNotePaneHeight = 200.dp

/** Semi-transparent create/edit panel over the paused frame. */
@Composable
fun ScreenshotEditDialog(
    shot: ScreenshotShot,
    cues: List<SubtitleCue>,
    customTagNames: List<String>,
    capturing: Boolean = false,
    onSave: (ScreenshotShot) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember(shot.id) { mutableStateOf(shot.title) }
    var notes by remember(shot.id) { mutableStateOf(shot.notes) }
    var noteText by remember(shot.id) { mutableStateOf("") }
    var editingNoteId by remember(shot.id) { mutableStateOf<String?>(null) }
    var checked by remember(shot.id) { mutableStateOf(setOf<Int>()) }
    var showTagPicker by remember { mutableStateOf(false) }
    var tagPickerForSave by remember { mutableStateOf(false) }
    var askMissingTag by remember { mutableStateOf(false) }
    var deleteNoteId by remember { mutableStateOf<String?>(null) }
    var showThemePicker by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(ScreenshotEditChrome.Default) }
    var panelX by remember { mutableFloatStateOf(0f) }
    var panelY by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val window = remember(cues, shot.time) { nearbyCuesAroundTime(cues, shot.time) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val knownTags = remember(customTagNames) { PRESET_TAGS + customTagNames }

    LaunchedEffect(Unit) {
        val (kindWire, opacity) = prefs.screenshotEditChromeOnce()
        chrome = ScreenshotEditChrome(
            kind = ScreenshotEditThemeKind.fromWire(kindWire),
            opacity = opacity,
        ).clamped
    }

    fun persistChrome(next: ScreenshotEditChrome) {
        val clamped = next.clamped
        chrome = clamped
        scope.launch {
            prefs.saveScreenshotEditChrome(clamped.kind.toWire(), clamped.opacity)
        }
    }

    fun scrollToCenter() {
        val index = window.centerInWindow ?: return
        scope.launch {
            listState.scrollToItem(index)
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launch
            val viewport = layout.viewportEndOffset - layout.viewportStartOffset
            val target = layout.viewportStartOffset + (viewport - item.size) / 2
            val delta = item.offset - target
            if (delta != 0) listState.scrollBy(delta.toFloat())
        }
    }

    LaunchedEffect(shot.id, window.centerInWindow) {
        scrollToCenter()
    }

    fun clearNoteEditor() {
        editingNoteId = null
        noteText = ""
    }

    fun addNote() {
        val text = noteText.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val note = ScreenshotNote(
            id = newScreenshotId(),
            text = text,
            createdAt = now,
            updatedAt = now,
            box = null,
        )
        notes = notes + note
        clearNoteEditor()
    }

    fun updateNote() {
        val id = editingNoteId ?: return
        val text = noteText.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        notes = notes.map { note ->
            if (note.id == id) note.copy(text = text, updatedAt = now) else note
        }
    }

    fun saveAsNewNote() {
        val text = noteText.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val note = ScreenshotNote(
            id = newScreenshotId(),
            text = text,
            createdAt = now,
            updatedAt = now,
            box = null,
        )
        notes = notes + note
        editingNoteId = note.id
        noteText = text
    }

    fun insertCheckedCues() {
        val selected = window.cues.filter { it.index in checked }
        if (selected.isEmpty()) return
        val joined = joinCueTextsForNote(selected.map { it.text })
        noteText = appendJoinedCueTexts(noteText, joined)
        checked = emptySet()
    }

    fun buildShot(now: Long = System.currentTimeMillis()): ScreenshotShot =
        shot.copy(title = title.take(4000), notes = notes, updatedAt = now)

    fun commitSave() {
        onSave(buildShot())
    }

    fun trySave() {
        if (capturing) return
        if (titleHasKnownTag(title, knownTags)) {
            commitSave()
        } else {
            askMissingTag = true
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Avoid the system shoving this dialog blindly; we pad with IME insets ourselves.
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeOpen = imeBottomPx > 0

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = if (imeOpen) Alignment.TopCenter else Alignment.Center,
        ) {
            val maxOffsetX = with(density) { (maxWidth.toPx() / 2f) }
            val maxOffsetY = with(density) { (maxHeight.toPx() / 2f) }
            LaunchedEffect(imeOpen, maxOffsetY) {
                if (imeOpen) {
                    // Keep a manually dragged panel from sitting under the keyboard.
                    panelY = panelY.coerceIn(-maxOffsetY, 0f)
                }
            }

            Surface(
                color = chrome.panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .padding(top = if (imeOpen) 8.dp else 0.dp)
                    .offset { IntOffset(panelX.roundToInt(), panelY.roundToInt()) }
                    .widthIn(max = 920.dp)
                    .heightIn(max = maxHeight)
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(if (imeOpen) 1f else 0.76f)
                    .border(1.dp, chrome.border, RoundedCornerShape(10.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "创建/编辑截图",
                        color = chrome.text,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(maxOffsetX, maxOffsetY, imeOpen) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    panelX = (panelX + drag.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    val maxY = if (imeOpen) 0f else maxOffsetY
                                    panelY = (panelY + drag.y).coerceIn(-maxOffsetY, maxY)
                                }
                            }
                            .padding(bottom = 4.dp),
                    )
                    if (capturing) {
                        Text(
                            "正在截取画面并保存到文件夹…可先改标题和笔记",
                            color = CheckYellow,
                            fontSize = 12.sp,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("截图标题", color = chrome.text, fontSize = 14.sp)
                        DarkField(
                            value = title,
                            onValueChange = { title = it.take(4000) },
                            singleLine = true,
                            chrome = chrome,
                            modifier = Modifier.weight(1f),
                        )
                        ThemedButton(chrome = chrome, onClick = {
                            tagPickerForSave = false
                            showTagPicker = true
                        }) { Text("选择标签", color = chrome.text, fontSize = 13.sp) }
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val sideBySide = maxWidth >= SideBySideMinWidth
                        val cuePane: @Composable (Modifier) -> Unit = { paneModifier ->
                            Column(
                                modifier = paneModifier,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("从笔记中提取", color = chrome.text, fontSize = 14.sp)
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(chrome.field, RoundedCornerShape(6.dp))
                                        .border(1.dp, chrome.border, RoundedCornerShape(6.dp)),
                                ) {
                                    itemsIndexed(window.cues, key = { _, cue -> cue.index }) { index, cue ->
                                        val isCenter = index == window.centerInWindow
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isCenter) CenterCueBg else Color.Transparent)
                                                .clickable {
                                                    checked = if (cue.index in checked) {
                                                        checked - cue.index
                                                    } else {
                                                        checked + cue.index
                                                    }
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = cue.index in checked,
                                                onCheckedChange = { on ->
                                                    checked = if (on) {
                                                        checked + cue.index
                                                    } else {
                                                        checked - cue.index
                                                    }
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = CenterCueBg,
                                                    checkmarkColor = CheckYellow,
                                                    uncheckedColor = chrome.text.copy(alpha = 0.7f),
                                                ),
                                            )
                                            Text(
                                                text = "[${formatClock(cue.start)}] ${cue.text.replace("\n", " / ")}",
                                                color = if (isCenter) Color.White else chrome.text,
                                                fontSize = 12.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ThemedButton(
                                        chrome = chrome,
                                        enabled = checked.isNotEmpty(),
                                        onClick = { insertCheckedCues() },
                                    ) {
                                        Text(
                                            if (sideBySide) "传入右侧输入框内" else "传入下方输入框内",
                                            color = chrome.text,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    ThemedButton(
                                        chrome = chrome,
                                        enabled = window.centerInWindow != null,
                                        onClick = { scrollToCenter() },
                                    ) { Text("回到中心", color = chrome.text, fontSize = 13.sp) }
                                }
                            }
                        }
                        val notePane: @Composable (Modifier) -> Unit = { paneModifier ->
                            Column(
                                modifier = paneModifier,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("笔记内容", color = chrome.text, fontSize = 14.sp)
                                DarkField(
                                    value = noteText,
                                    onValueChange = { noteText = it },
                                    singleLine = false,
                                    placeholder = "笔记内容",
                                    chrome = chrome,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (editingNoteId == null) {
                                        ThemedButton(
                                            chrome = chrome,
                                            enabled = noteText.isNotBlank(),
                                            onClick = { addNote() },
                                        ) { Text("添加笔记", color = chrome.text, fontSize = 13.sp) }
                                    } else {
                                        ThemedButton(
                                            chrome = chrome,
                                            enabled = noteText.isNotBlank(),
                                            onClick = { updateNote() },
                                        ) { Text("更新笔记", color = chrome.text, fontSize = 13.sp) }
                                        Spacer(Modifier.width(6.dp))
                                        ThemedButton(
                                            chrome = chrome,
                                            enabled = noteText.isNotBlank(),
                                            onClick = { saveAsNewNote() },
                                        ) { Text("保存为新笔记", color = chrome.text, fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                        if (sideBySide) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                cuePane(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                                notePane(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        } else {
                            // Stacked: grow the cue list with the taller panel; keep notes compact.
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                cuePane(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                                notePane(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(StackedNotePaneHeight),
                                )
                            }
                        }
                    }

                    val noteScroll = rememberScrollState()
                    val notesOverflow = noteScroll.maxValue > 0
                    if (notes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(noteScroll),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                notes.forEach { note ->
                                    NoteChip(
                                        note = note,
                                        selected = note.id == editingNoteId,
                                        onClick = {
                                            if (editingNoteId == note.id) {
                                                clearNoteEditor()
                                            } else {
                                                editingNoteId = note.id
                                                noteText = note.text
                                            }
                                        },
                                        onDelete = { deleteNoteId = note.id },
                                    )
                                }
                            }
                            if (notesOverflow) {
                                Text("按住标签左右拖动", color = chrome.muted, fontSize = 11.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemedButton(chrome = chrome, onClick = { showThemePicker = true }) {
                            Text("主题", color = chrome.text, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        ThemedButton(chrome = chrome, onClick = onCancel) {
                            Text("取消", color = chrome.text, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        ThemedButton(chrome = chrome, enabled = !capturing, onClick = { trySave() }) {
                            Text(
                                if (capturing) "截取中…" else "保存",
                                color = chrome.text,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showThemePicker) {
        ScreenshotThemePickerDialog(
            chrome = chrome,
            onChromeChange = { persistChrome(it) },
            onDismiss = { showThemePicker = false },
        )
    }

    if (showTagPicker) {
        TagEditDialog(
            single = true,
            initialTags = emptyList(),
            initialNote = "",
            customNames = customTagNames,
            showNoteField = false,
            dialogTitle = "选择标签",
            description = "选择标签，确认后接到标题后面",
            onDismiss = {
                showTagPicker = false
                tagPickerForSave = false
            },
            onConfirm = { tags, _, _ ->
                if (tags.isEmpty()) {
                    showTagPicker = false
                    tagPickerForSave = false
                    return@TagEditDialog
                }
                title = appendScreenshotTitleTags(title, tags)
                showTagPicker = false
                if (tagPickerForSave) {
                    tagPickerForSave = false
                    commitSave()
                }
            },
        )
    }

    if (askMissingTag) {
        AlertDialog(
            onDismissRequest = { askMissingTag = false },
            title = { Text("还没有标签备注") },
            text = {
                Text("系统检测到你没有为此截图主题备注标签，这将很不利于后期复习整理，你确定不需要加上标签备注吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    askMissingTag = false
                    tagPickerForSave = true
                    showTagPicker = true
                }) { Text("我要打标签") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askMissingTag = false
                    commitSave()
                }) { Text("不需要标签") }
            },
        )
    }

    deleteNoteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteNoteId = null },
            title = { Text("删除这条笔记？") },
            text = { Text("删除后，这条笔记会从当前截图中去掉。") },
            confirmButton = {
                TextButton(onClick = {
                    notes = notes.filterNot { it.id == id }
                    if (editingNoteId == id) clearNoteEditor()
                    deleteNoteId = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteNoteId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScreenshotThemePickerDialog(
    chrome: ScreenshotEditChrome,
    onChromeChange: (ScreenshotEditChrome) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("截图对话框主题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("风格")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = chrome.kind == ScreenshotEditThemeKind.Dim,
                        onClick = {
                            onChromeChange(chrome.copy(kind = ScreenshotEditThemeKind.Dim))
                        },
                        label = { Text("暗淡") },
                    )
                    FilterChip(
                        selected = chrome.kind == ScreenshotEditThemeKind.Bright,
                        onClick = {
                            onChromeChange(chrome.copy(kind = ScreenshotEditThemeKind.Bright))
                        },
                        label = { Text("明亮") },
                    )
                }
                Text("透明度 ${(chrome.opacity * 100).toInt()}%")
                Text(
                    "数值越低，越能透过对话框看见后面的截图画面。",
                    color = Color.Gray,
                    fontSize = 12.sp,
                )
                Slider(
                    value = chrome.opacity,
                    onValueChange = { onChromeChange(chrome.copy(opacity = it)) },
                    valueRange = ScreenshotEditChrome.MIN_OPACITY..ScreenshotEditChrome.MAX_OPACITY,
                )
                TextButton(onClick = { onChromeChange(ScreenshotEditChrome.Default) }) {
                    Text("恢复默认", color = AccentPurple)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteChip(
    note: ScreenshotNote,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .widthIn(min = 88.dp, max = 140.dp)
                .background(
                    if (selected) NoteChipActiveBg else NoteChipIdleBg,
                    RoundedCornerShape(8.dp),
                )
                .border(
                    1.dp,
                    if (selected) NoteChipActiveBorder else NoteChipIdleBorder,
                    RoundedCornerShape(8.dp),
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = noteTagLabel(note.text),
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = noteChipTimeLabel(note),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("删除笔记") },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DarkField(
    value: String,
    onValueChange: (String) -> Unit,
    chrome: ScreenshotEditChrome,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String = "",
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .background(chrome.field, RoundedCornerShape(6.dp))
            .border(1.dp, chrome.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = chrome.muted, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = chrome.text, fontSize = 14.sp),
            cursorBrush = SolidColor(AccentPurple),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleLine) Modifier else Modifier.fillMaxHeight())
                .onFocusEvent { state ->
                    if (state.isFocused) {
                        scope.launch {
                            kotlinx.coroutines.delay(64)
                            bringIntoView.bringIntoView()
                        }
                    }
                },
        )
    }
}

@Composable
private fun ThemedButton(
    chrome: ScreenshotEditChrome,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (enabled) chrome.button else chrome.buttonDisabled,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                if (enabled) chrome.border else chrome.borderDisabled,
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
