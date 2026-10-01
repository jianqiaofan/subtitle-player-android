package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class StudyLogUi(
    val total: String,
    val lines: List<String>,
)

data class LeaveSyncUi(
    val enableSubtitles: Boolean,
    val enableTags: Boolean,
)

@Composable
fun StudyLogDialog(log: StudyLogUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学习记录") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("学习总时长：${log.total}")
                if (log.lines.isEmpty()) {
                    Text("还没有播放记录。")
                } else {
                    log.lines.forEach { line ->
                        Text(line, modifier = Modifier.align(Alignment.Start))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun LeaveSyncDialog(
    prompt: LeaveSyncUi,
    onSync: (subtitles: Boolean, tags: Boolean, remember: Boolean) -> Unit,
    onSkip: (subtitles: Boolean, tags: Boolean, remember: Boolean) -> Unit,
) {
    var subtitles by rememberSaveable { mutableStateOf(prompt.enableSubtitles) }
    var tags by rememberSaveable { mutableStateOf(prompt.enableTags) }
    var remember by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onSkip(subtitles, tags, remember) },
        title = { Text("同步这次的修改？") },
        text = {
            Column {
                CheckRow("同步字幕", subtitles, prompt.enableSubtitles) { subtitles = it }
                CheckRow("同步标签", tags, prompt.enableTags) { tags = it }
                CheckRow(
                    "不再询问，在本设备，该视频下回采用相同的同步策略",
                    remember,
                    enabled = true,
                ) { remember = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSync(subtitles, tags, remember) }) { Text("同步") }
        },
        dismissButton = {
            TextButton(onClick = { onSkip(subtitles, tags, remember) }) { Text("暂不同步") }
        },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChecked, enabled = enabled)
        Text(label)
    }
}
