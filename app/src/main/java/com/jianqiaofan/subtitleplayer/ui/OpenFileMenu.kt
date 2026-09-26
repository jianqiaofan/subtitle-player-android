package com.jianqiaofan.subtitleplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.model.recentMediaMenuLabel

@Composable
fun OpenFileMenuButton(
    recents: List<RecentMedia>,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentMedia) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    val primary = recents.take(6)
    val extra = recents.drop(6).take(9)
    Box {
        TextButton(onClick = { expanded = true }) { Text("打开文件") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; more = false }) {
            DropdownMenuItem(
                text = { Text("打开媒体") },
                onClick = {
                    expanded = false
                    onOpenPicker()
                },
            )
            primary.forEach { item ->
                DropdownMenuItem(
                    text = { Text(recentMediaMenuLabel(item, recents)) },
                    onClick = {
                        expanded = false
                        onOpenRecent(item)
                    },
                )
            }
            if (extra.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("更多") },
                    onClick = { more = true },
                )
            }
        }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            extra.forEach { item ->
                DropdownMenuItem(
                    text = { Text(recentMediaMenuLabel(item, recents)) },
                    onClick = {
                        more = false
                        expanded = false
                        onOpenRecent(item)
                    },
                )
            }
        }
    }
}
