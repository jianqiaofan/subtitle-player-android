package com.jianqiaofan.subtitleplayer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * Reusable tip dialog: title `tip`, dismissible with 「下回不再提醒」 + 「我知道了」.
 */
@Composable
fun TipDialog(
    message: String,
    onKnown: (dontRemind: Boolean) -> Unit,
    onDismiss: () -> Unit = { onKnown(false) },
    title: String = "tip",
    checkboxLabel: String = "下回不再提醒",
    confirmLabel: String = "我知道了",
) {
    var dontRemind by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontRemind, onCheckedChange = { dontRemind = it })
                    Text(checkboxLabel)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onKnown(dontRemind) }) { Text(confirmLabel) }
        },
    )
}
