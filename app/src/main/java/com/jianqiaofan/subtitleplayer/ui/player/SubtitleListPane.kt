package com.jianqiaofan.subtitleplayer.ui.player

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.display.SubtitleListDensity
import com.jianqiaofan.subtitleplayer.domain.display.formatCueListBody
import com.jianqiaofan.subtitleplayer.domain.display.joinSelectedCueTexts
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatCueListHeader
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.TagListFilter
import com.jianqiaofan.subtitleplayer.domain.tags.buildListRows
import com.jianqiaofan.subtitleplayer.domain.tags.filterButtons
import com.jianqiaofan.subtitleplayer.domain.tags.primaryTag
import com.jianqiaofan.subtitleplayer.domain.tags.shouldShowTagFilter
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleBodyDimmed
import com.jianqiaofan.subtitleplayer.domain.tags.tagCounts
import com.jianqiaofan.subtitleplayer.domain.tags.tagPalette
import com.jianqiaofan.subtitleplayer.domain.screenshot.PlaybackRow
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.mergeScreenshotRows
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotListLabel
import com.jianqiaofan.subtitleplayer.domain.tags.TagAlignment
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.NoteGold
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubtitleListPane(
    cues: List<SubtitleCue>,
    currentCueIndex: Int,
    tracksEmpty: Boolean,
    density: SubtitleListDensity,
    listState: LazyListState,
    userScrollConnection: NestedScrollConnection,
    selectionMode: Boolean,
    selectedIndices: Set<Int>,
    menuIndex: Int?,
    onMenuIndexChange: (Int?) -> Unit,
    /** Fired for row menus that are not tracked by [menuIndex] (screenshot / unmatched). */
    onContextMenuOpenChange: (Boolean) -> Unit = {},
    onDensityToggle: () -> Unit,
    onCueClick: (Int) -> Unit,
    onEnterSelection: (Int) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onExitSelection: () -> Unit,
    onRepeat: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    attached: Map<Int, TagEntry> = emptyMap(),
    unmatched: List<TagEntry> = emptyList(),
    tagFilter: TagListFilter = TagListFilter(),
    onTagFilter: (TagListFilter) -> Unit = {},
    onJumpTagged: (Boolean) -> Unit = {},
    onNoteClick: (Int) -> Unit = {},
    onTag: (List<Int>) -> Unit = {},
    onClearTags: (List<Int>) -> Unit = {},
    onUnmatchedClick: (TagEntry) -> Unit = {},
    onAttachUnmatched: (String) -> Unit = {},
    onDeleteUnmatched: (String) -> Unit = {},
    panelBackground: Color = SurfacePanel,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    listTitle: String = "字幕列表",
    allowEdit: Boolean = true,
    timelineSteps: List<Pair<Int, String>> = emptyList(),
    selectedTimelineStep: Int = 0,
    onTimelineStep: (Int) -> Unit = {},
    emptyHint: String? = null,
    screenshots: List<ScreenshotShot> = emptyList(),
    onScreenshotClick: (String) -> Unit = {},
    onScreenshotView: (String) -> Unit = {},
    onScreenshotEdit: (String) -> Unit = {},
    onScreenshotDelete: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val alignment = TagAlignment(attached, unmatched)
    val rows = mergeScreenshotRows(buildListRows(cues.size, alignment, tagFilter), cues, screenshots)
    val shotsById = screenshots.associateBy { it.id }
    val counts = tagCounts(alignment)

    Column(modifier = modifier.fillMaxSize().background(panelBackground)) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (selectionMode) "已选 ${selectedIndices.size}" else listTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                val text = joinSelectedCueTexts(cues, selectedIndices)
                                if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                            },
                            enabled = selectedIndices.isNotEmpty(),
                        ) { Text("复制") }
                        TextButton(
                            onClick = {
                                val text = joinSelectedCueTexts(cues, selectedIndices)
                                if (text.isEmpty()) return@TextButton
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "翻译 / 分享"),
                                )
                            },
                            enabled = selectedIndices.isNotEmpty(),
                        ) { Text("翻译") }
                        if (selectedIndices.size == 1) {
                            val only = selectedIndices.first()
                            TextButton(onClick = { onRepeat(only); onExitSelection() }) {
                                Text("重复")
                            }
                            if (allowEdit) {
                                TextButton(onClick = { onEdit(only); onExitSelection() }) {
                                    Text("编辑")
                                }
                            }
                        }
                        TextButton(
                            onClick = { onTag(selectedIndices.toList()); onExitSelection() },
                            enabled = selectedIndices.isNotEmpty(),
                        ) { Text("标签") }
                        TextButton(
                            onClick = { onClearTags(selectedIndices.toList()); onExitSelection() },
                            enabled = selectedIndices.isNotEmpty(),
                        ) { Text("清除") }
                        TextButton(onClick = onExitSelection) { Text("取消") }
                    } else {
                        TextButton(onClick = onDensityToggle) {
                            Text(density.label)
                        }
                    }
                }
            }
        }
        if (timelineSteps.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                timelineSteps.forEach { (seconds, label) ->
                    TextButton(onClick = { onTimelineStep(seconds) }) {
                        Text(label, color = if (seconds == selectedTimelineStep) AccentPurple else Color.White)
                    }
                }
            }
        }

        if (shouldShowTagFilter(alignment)) {
            TagFilterBar(
                buttons = filterButtons(counts),
                unmatchedCount = unmatched.size,
                filter = tagFilter,
                onAll = { onTagFilter(TagListFilter()) },
                onToggle = { name ->
                    onTagFilter(com.jianqiaofan.subtitleplayer.domain.tags.toggleTagFilter(tagFilter, name))
                },
                onUnmatched = {
                    onTagFilter(
                        if (tagFilter.showUnmatched) TagListFilter() else TagListFilter(showUnmatched = true),
                    )
                },
                onPrevious = { onJumpTagged(false) },
                onNext = { onJumpTagged(true) },
            )
        }

        if (cues.isEmpty() && unmatched.isEmpty() && screenshots.isEmpty()) {
            Text(
                text = emptyHint ?: if (tracksEmpty) "未找到字幕" else "字幕为空",
                color = OnDarkMuted,
                modifier = Modifier.padding(16.dp),
            )
            return
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(userScrollConnection),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = maxHeight / 2),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { row ->
                    when (row) {
                        is PlaybackRow.Cue -> "cue-${row.cueIndex}"
                        is PlaybackRow.Shot -> "shot-${row.id}"
                        is PlaybackRow.Unmatched -> "tag-${row.entryId}"
                    }
                }) { row ->
                    when (row) {
                        is PlaybackRow.Shot -> {
                            val shot = shotsById[row.id] ?: return@items
                            ScreenshotRow(
                                shot = shot,
                                onClick = { onScreenshotClick(shot.id) },
                                onView = { onScreenshotView(shot.id) },
                                onEdit = { onScreenshotEdit(shot.id) },
                                onDelete = { onScreenshotDelete(shot.id) },
                                onMenuOpenChange = onContextMenuOpenChange,
                            )
                        }
                        is PlaybackRow.Cue -> {
                            val index = row.cueIndex
                            val cue = cues[index]
                            val entry = attached[index]
                            CueRow(
                                cue = cue,
                                entry = entry,
                                density = density,
                                highlighted = index == currentCueIndex,
                                selected = index in selectedIndices,
                                selectionMode = selectionMode,
                                menuExpanded = menuIndex == index && !selectionMode,
                                onMenuIndexChange = onMenuIndexChange,
                                onClick = {
                                    if (selectionMode) onToggleSelection(index) else onCueClick(index)
                                },
                                onLongClick = {
                                    if (selectionMode) onToggleSelection(index) else onMenuIndexChange(index)
                                },
                                onToggleSelection = { onToggleSelection(index) },
                                onNoteClick = { onNoteClick(index) },
                                onRepeat = { onRepeat(index); onMenuIndexChange(null) },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(cue.text))
                                    onMenuIndexChange(null)
                                },
                                allowEdit = allowEdit,
                                onEdit = { onMenuIndexChange(null); onEdit(index) },
                                onMultiSelect = { onMenuIndexChange(null); onEnterSelection(index) },
                                onTag = { onMenuIndexChange(null); onTag(listOf(index)) },
                                onClearTags = { onMenuIndexChange(null); onClearTags(listOf(index)) },
                                onMenuOpenChange = onContextMenuOpenChange,
                            )
                        }
                        is PlaybackRow.Unmatched -> {
                            val entry = unmatched.firstOrNull { it.id == row.entryId } ?: return@items
                            UnmatchedRow(
                                entry = entry,
                                onClick = { onUnmatchedClick(entry) },
                                onAttach = { onAttachUnmatched(entry.id) },
                                onDelete = { onDeleteUnmatched(entry.id) },
                                onMenuOpenChange = onContextMenuOpenChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotRow(
    shot: ScreenshotShot,
    onClick: () -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var confirmStep by remember { mutableStateOf(0) }
    DisposableEffect(menu) {
        if (!menu) return@DisposableEffect onDispose { }
        onMenuOpenChange(true)
        onDispose { onMenuOpenChange(false) }
    }
    val rowBg = Color(0xFF3A2458)
    val rowFg = Color(0xFFE2C6FF)
    Box {
        Text(
            text = screenshotListLabel(shot),
            color = rowFg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("查看截图") },
                onClick = {
                    menu = false
                    onView()
                },
            )
            DropdownMenuItem(
                text = { Text("编辑截图") },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除截图") },
                onClick = {
                    menu = false
                    confirmStep = 1
                },
            )
        }
    }
    if (confirmStep == 1) {
        AlertDialog(
            onDismissRequest = { confirmStep = 0 },
            title = { Text("删除截图") },
            text = {
                Text("删除这张截图和它的笔记？云端的这张截图也会一并删除，今后无法再从线上同步回来。")
            },
            confirmButton = {
                TextButton(onClick = { confirmStep = 2 }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStep = 0 }) { Text("取消") }
            },
        )
    }
    if (confirmStep == 2) {
        AlertDialog(
            onDismissRequest = { confirmStep = 0 },
            title = { Text("确定删除？") },
            text = {
                Text("确定删除？本机和云端的这张截图都会去掉，以后不能再线上同步。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStep = 0
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStep = 0 }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CueRow(
    cue: SubtitleCue,
    entry: TagEntry?,
    density: SubtitleListDensity,
    highlighted: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    menuExpanded: Boolean,
    onMenuIndexChange: (Int?) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onNoteClick: () -> Unit,
    onRepeat: () -> Unit,
    onCopy: () -> Unit,
    allowEdit: Boolean,
    onEdit: () -> Unit,
    onMultiSelect: () -> Unit,
    onTag: () -> Unit,
    onClearTags: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    val compact = density == SubtitleListDensity.Compact
    val tags = entry?.tags.orEmpty()
    val note = entry?.note.orEmpty()
    val bar = primaryTag(tags)?.let { Color(tagPalette(it).background) }
    val bodyColor = when {
        highlighted -> AccentPurple
        subtitleBodyDimmed(tags) -> OnDarkMuted
        else -> Color.Unspecified
    }
    val verticalPad = if (compact) 2.dp else 10.dp
    DisposableEffect(menuExpanded) {
        if (!menuExpanded) return@DisposableEffect onDispose { }
        onMenuOpenChange(true)
        onDispose { onMenuOpenChange(false) }
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .background(
                    when {
                        selected -> AccentPurple.copy(alpha = 0.22f)
                        highlighted -> AccentPurple.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    },
                )
                .then(
                    if (selected) Modifier.border(1.dp, AccentPurple.copy(alpha = 0.5f)) else Modifier,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            if (bar != null) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(bar),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 12.dp, vertical = verticalPad),
                verticalAlignment = Alignment.Top,
            ) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                }
                Column(Modifier.weight(1f)) {
                    if (!compact) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(formatCueListHeader(cue), style = MaterialTheme.typography.bodyMedium, color = bodyColor)
                            TagChips(tags)
                        }
                        Text(
                            text = cue.text.replace("\n", " / "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = bodyColor,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TagChips(tags)
                            Text(
                                text = formatCueListBody(cue).ifBlank { formatCueListHeader(cue) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = bodyColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (note.isNotBlank()) {
                        Text(
                            text = note.replace("\n", " "),
                            color = NoteGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .combinedClickable(onClick = onNoteClick, onLongClick = onNoteClick),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuIndexChange(null) }) {
            DropdownMenuItem(text = { Text("重复播放") }, onClick = onRepeat)
            DropdownMenuItem(text = { Text("复制") }, onClick = onCopy)
            if (allowEdit) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = onEdit)
            }
            DropdownMenuItem(text = { Text("标签") }, onClick = onTag)
            DropdownMenuItem(text = { Text("清除标签") }, onClick = onClearTags)
            DropdownMenuItem(text = { Text("多选") }, onClick = onMultiSelect)
        }
    }
}

@Composable
private fun TagChips(tags: List<String>) {
    tags.forEach { name ->
        val palette = tagPalette(name)
        Text(
            text = name,
            color = Color(palette.foreground),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(palette.background))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun UnmatchedRow(
    entry: TagEntry,
    onClick: () -> Unit,
    onAttach: () -> Unit,
    onDelete: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(menu) {
        if (!menu) return@DisposableEffect onDispose { }
        onMenuOpenChange(true)
        onDispose { onMenuOpenChange(false) }
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("未挂上  ${formatCueListHeader(com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue(entry.index, entry.start, entry.end, entry.text)).substringAfter(". ")}", color = OnDarkMuted, style = MaterialTheme.typography.labelMedium)
            TagChips(entry.tags)
            Text(entry.text.replace("\n", " / "), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            if (entry.note.isNotBlank()) {
                Text(entry.note.replace("\n", " "), color = NoteGold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("挂到当前句") }, onClick = { menu = false; onAttach() })
            DropdownMenuItem(text = { Text("删除这条标签") }, onClick = { menu = false; onDelete() })
        }
    }
}
