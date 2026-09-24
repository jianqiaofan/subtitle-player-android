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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.display.SubtitleListDensity
import com.jianqiaofan.subtitleplayer.domain.display.formatCueListBody
import com.jianqiaofan.subtitleplayer.domain.display.joinSelectedCueTexts
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatCueListLine
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
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
    onDensityToggle: () -> Unit,
    onCueClick: (Int) -> Unit,
    onEnterSelection: (Int) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onExitSelection: () -> Unit,
    onRepeat: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    panelBackground: Color = SurfacePanel,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                    text = if (selectionMode) "已选 ${selectedIndices.size}" else "字幕列表",
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
                            TextButton(onClick = { onEdit(only); onExitSelection() }) {
                                Text("编辑")
                            }
                        }
                        TextButton(onClick = onExitSelection) { Text("取消") }
                    } else {
                        TextButton(onClick = onDensityToggle) {
                            Text(density.label)
                        }
                    }
                }
            }
        }

        if (cues.isEmpty()) {
            Text(
                text = if (tracksEmpty) "未找到字幕" else "字幕为空",
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
                itemsIndexed(cues, key = { _, cue -> cue.index to cue.start }) { index, cue ->
                    val highlighted = index == currentCueIndex
                    val selected = index in selectedIndices
                    val verticalPad = if (density == SubtitleListDensity.Compact) 2.dp else 10.dp
                    val lineText = if (density == SubtitleListDensity.Compact) {
                        formatCueListBody(cue)
                    } else {
                        formatCueListLine(cue)
                    }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        selected -> AccentPurple.copy(alpha = 0.22f)
                                        highlighted -> AccentPurple.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = AccentPurple.copy(alpha = 0.5f),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            onToggleSelection(index)
                                        } else {
                                            onCueClick(index)
                                        }
                                    },
                                    onLongClick = {
                                        if (selectionMode) {
                                            onToggleSelection(index)
                                        } else {
                                            onMenuIndexChange(index)
                                        }
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = verticalPad),
                            verticalAlignment = Alignment.Top,
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { onToggleSelection(index) },
                                )
                            }
                            Text(
                                text = lineText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (highlighted) AccentPurple else Color.Unspecified,
                                softWrap = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SubtitleItemMenu(
                            expanded = menuIndex == index && !selectionMode,
                            onDismiss = { onMenuIndexChange(null) },
                            onRepeat = {
                                onRepeat(index)
                                onMenuIndexChange(null)
                            },
                            onCopy = {
                                clipboard.setText(AnnotatedString(cue.text))
                                onMenuIndexChange(null)
                            },
                            onEdit = {
                                onMenuIndexChange(null)
                                onEdit(index)
                            },
                            onMultiSelect = {
                                onMenuIndexChange(null)
                                onEnterSelection(index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRepeat: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onMultiSelect: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("重复播放") },
            onClick = onRepeat,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("复制") },
            onClick = onCopy,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("编辑") },
            onClick = onEdit,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("多选") },
            onClick = onMultiSelect,
        )
    }
}
