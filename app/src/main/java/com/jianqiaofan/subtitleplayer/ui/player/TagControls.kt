package com.jianqiaofan.subtitleplayer.ui.player

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.jianqiaofan.subtitleplayer.domain.tags.PRESET_TAGS
import com.jianqiaofan.subtitleplayer.domain.tags.TAG_CATEGORIES
import com.jianqiaofan.subtitleplayer.domain.tags.TagListFilter
import com.jianqiaofan.subtitleplayer.domain.tags.normalizeCustomTagName
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import kotlin.math.roundToInt

private val TagPanelBg = Color(0xCC1A1A1A)
private val TagButtonBorder = Color(0xFF5A5A5A)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFilterBar(
    buttons: List<Pair<String, Int>>,
    unmatchedCount: Int,
    filter: TagListFilter,
    onAll: () -> Unit,
    onToggle: (String) -> Unit,
    onUnmatched: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        buttons.forEach { (name, count) ->
            val wide = name.length > 16
            FilterChip(
                selected = !filter.showUnmatched && name in filter.selectedTags,
                onClick = { onToggle(name) },
                label = {
                    Text(
                        "$name $count",
                        maxLines = if (wide) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = if (wide) Modifier.fillMaxWidth() else Modifier,
            )
        }
        if (unmatchedCount > 0) {
            FilterChip(
                selected = filter.showUnmatched,
                onClick = onUnmatched,
                label = { Text("未挂上 $unmatchedCount") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = filter.isAll, onClick = onAll, label = { Text("全部") })
            TextButton(onClick = onPrevious) { Text("上一条") }
            TextButton(onClick = onNext) { Text("下一条") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditDialog(
    single: Boolean,
    initialTags: List<String>,
    initialNote: String,
    customNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (tags: List<String>, note: String, applyNote: Boolean) -> Unit,
    showNoteField: Boolean = true,
    dialogTitle: String? = null,
    description: String? = null,
) {
    var selected by remember { mutableStateOf(initialTags.toSet()) }
    var note by remember { mutableStateOf(if (single) initialNote else "") }
    var applyNote by remember { mutableStateOf(single && showNoteField) }
    var customDraft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var panelX by remember { mutableFloatStateOf(0f) }
    var panelY by remember { mutableFloatStateOf(0f) }
    val customs = (customNames + selected.filter { it !in PRESET_TAGS }).distinct()
    val titleText = dialogTitle ?: if (single) "标签" else "为所选字幕打标签"

    Dialog(
        onDismissRequest = onDismiss,
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
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        val density = LocalDensity.current
        val imeOpen = WindowInsets.ime.getBottom(density) > 0

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = if (imeOpen) Alignment.TopCenter else Alignment.Center,
        ) {
            val maxOffsetX = with(density) { (maxWidth.toPx() / 2f) }
            val maxOffsetY = with(density) { (maxHeight.toPx() / 2f) }
            LaunchedEffect(imeOpen, maxOffsetY) {
                if (imeOpen) panelY = panelY.coerceIn(-maxOffsetY, 0f)
            }

            Surface(
                color = TagPanelBg,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .padding(top = if (imeOpen) 8.dp else 0.dp)
                    .offset { IntOffset(panelX.roundToInt(), panelY.roundToInt()) }
                    .widthIn(max = 560.dp)
                    .heightIn(max = if (imeOpen) maxHeight else 640.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(if (imeOpen) 1f else 0.85f)
                    .border(1.dp, TagButtonBorder, RoundedCornerShape(10.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(maxOffsetX, maxOffsetY, imeOpen) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    panelX = (panelX + drag.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    val maxY = if (imeOpen) 0f else maxOffsetY
                                    panelY = (panelY + drag.y).coerceIn(-maxOffsetY, maxY)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = titleText,
                            color = Color.White,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = Color.White,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!description.isNullOrBlank()) {
                            Text(description, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                        }
                        TAG_CATEGORIES.forEach { (category, names) ->
                            Text(category, color = Color.White, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                names.forEach { name ->
                                    FilterChip(
                                        selected = name in selected,
                                        onClick = {
                                            selected = if (name in selected) {
                                                selected - name
                                            } else {
                                                selected + name
                                            }
                                        },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        Text("自定义", color = Color.White, fontSize = 14.sp)
                        if (customs.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                customs.forEach { name ->
                                    FilterChip(
                                        selected = name in selected,
                                        onClick = {
                                            selected = if (name in selected) {
                                                selected - name
                                            } else {
                                                selected + name
                                            }
                                        },
                                        label = {
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = customDraft,
                            onValueChange = {
                                customDraft = it.replace("\n", "").replace("\r", "").replace("\t", "").take(48)
                            },
                            label = { Text("新增自定义标签") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = {
                            val trimmed = customDraft.trim()
                            if (trimmed in PRESET_TAGS) {
                                selected = selected + trimmed
                                customDraft = ""
                                hint = null
                            } else {
                                val name = normalizeCustomTagName(customDraft)
                                if (name == null) {
                                    hint = "自定义标签需为 1～48 个字，不能包含换行或制表符"
                                } else {
                                    selected = selected + name
                                    customDraft = ""
                                    hint = null
                                }
                            }
                        }) { Text("加入", color = AccentPurple) }
                        if (hint != null) {
                            Text(hint.orEmpty(), color = Color(0xFFFFB0A8), fontSize = 12.sp)
                        }
                        if (showNoteField) {
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it },
                                label = { Text("备注") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                            )
                            if (!single) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = applyNote, onCheckedChange = { applyNote = it })
                                    Text("把备注设为上面的内容", color = Color.White)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        TextButton(onClick = { onConfirm(selected.toList(), note, applyNote) }) {
                            Text("确定", color = AccentPurple)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NoteEditDialog(
    note: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备注") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                minLines = 4,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SyncTagsExplainDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步标签文件") },
        text = {
            Text(
                "选择一个或多个从其它设备复制来的「字幕名.扩展名.tags.json」。" +
                    "只有当前视频文件夹里存在同名字幕才会接受；没有标签文件就复制，已有则合并；" +
                    "对不上的条目会留在「未挂上」。不修改字幕正文，也不改你选中的源文件。",
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("选择文件") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
