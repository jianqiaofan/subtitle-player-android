package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.tags.PRESET_TAGS
import com.jianqiaofan.subtitleplayer.domain.tags.TagListFilter
import com.jianqiaofan.subtitleplayer.domain.tags.normalizeCustomTagName
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple

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
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
) {
    var selected by remember { mutableStateOf(initialTags.toSet()) }
    var note by remember { mutableStateOf(if (single) initialNote else "") }
    var applyNote by remember { mutableStateOf(single) }
    var customDraft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val customs = (customNames + selected.filter { it !in PRESET_TAGS }).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single) "标签" else "为所选字幕打标签") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("预设标签")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESET_TAGS.forEach { name ->
                        FilterChip(
                            selected = name in selected,
                            onClick = {
                                selected = if (name in selected) selected - name else selected + name
                            },
                            label = { Text(name) },
                        )
                    }
                }
                if (customs.isNotEmpty()) {
                    Text("这部视频用过的自定义标签")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        customs.forEach { name ->
                            FilterChip(
                                selected = name in selected,
                                onClick = {
                                    selected = if (name in selected) selected - name else selected + name
                                },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = customDraft,
                    onValueChange = { customDraft = it.take(48) },
                    label = { Text("新增自定义标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    val name = normalizeCustomTagName(customDraft)
                    if (name == null) {
                        hint = "自定义标签需为 1～48 个字，且不能与预设标签重名"
                    } else {
                        selected = selected + name
                        customDraft = ""
                        hint = null
                    }
                }) { Text("加入", color = AccentPurple) }
                if (hint != null) Text(hint.orEmpty())
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
                        Text("把备注设为上面的内容")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList(), note, applyNote) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
