package com.jianqiaofan.subtitleplayer.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudPrompt
import com.jianqiaofan.subtitleplayer.domain.time.formatUtcLocal
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple

@Composable
fun CloudPromptDialog(
    prompt: CloudPrompt,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onPickPerson: (String) -> Unit,
) {
    when (prompt) {
        is CloudPrompt.Subtitles -> ChoiceDialog(
            title = "写入云端字幕？",
            body = prompt.lines.joinToString("\n") { "${it.fileName}    ${it.timeLabel}" },
            confirm = "写入",
            onConfirm = onAccept,
            onDismiss = onDismiss,
        )
        is CloudPrompt.Tags -> ChoiceDialog(
            title = "合并云端标签？",
            body = prompt.lines.joinToString("\n") { "${it.fileName}    ${it.timeLabel}" } +
                "\n\n按操作时间保留较新的标签和备注。手机上多出来的标签会留在本地，下次上传再送上去。",
            confirm = "合并",
            onConfirm = onAccept,
            onDismiss = onDismiss,
        )
        is CloudPrompt.Shares -> ShareDialog(prompt, onPickPerson, onDismiss)
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ShareDialog(
    prompt: CloudPrompt.Shares,
    onPickPerson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用别人分享的字幕？") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("这部视频在本地还没有字幕。选择一个人，会写入他共享的全部语言。")
                prompt.people.forEach { person ->
                    val mark = if (person.username == selected) "● " else "○ "
                    Text(
                        text = mark + person.username + "    " + formatUtcLocal(person.updatedAt) +
                            "\n" + person.subtitles.joinToString("、") { it.subtitleName },
                        color = if (person.username == selected) AccentPurple else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = person.username }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onPickPerson) },
                enabled = selected != null,
            ) { Text("下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
