package com.jianqiaofan.subtitleplayer.ui.library

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.domain.model.MediaEntry
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenMedia: (uri: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
            .getInstance(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(OpenPersistentTree()) { uri ->
        viewModel.onFolderPicked(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("字幕播放器") },
                actions = {
                    TextButton(onClick = { picker.launch(Unit) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text("选择文件夹", modifier = Modifier.padding(start = 6.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfacePanel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = AccentPurple,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = state.currentFolderName ?: "尚未选择学习文件夹",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Text(
                    text = "最近文件夹",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                if (state.recents.isEmpty()) {
                    Text(
                        text = "还没有打开过文件夹。选择一次后会记在这里，最多保留 8 个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkMuted,
                    )
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.recents.forEach { folder ->
                            FilterChip(
                                selected = folder.treeUri == state.currentTreeUri,
                                onClick = { viewModel.onRecentClicked(folder) },
                                label = { Text(folder.displayName) },
                            )
                        }
                    }
                }
            }
            if (state.loading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp), color = AccentPurple)
                }
            }
            if (!state.loading && state.currentTreeUri == null) {
                item { EmptyLibraryHint() }
            }
            if (!state.loading && state.currentTreeUri != null && state.media.isEmpty()) {
                item {
                    Text(
                        text = "这个文件夹里没有找到视频或音频。请确认媒体和字幕在同一层目录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkMuted,
                    )
                }
            }
            items(state.media, key = { it.documentUri }) { media ->
                MediaRow(media = media, onClick = { onOpenMedia(media.documentUri, media.displayName) })
            }
        }
    }
}

@Composable
private fun MediaRow(media: MediaEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = SurfacePanel),
        headlineContent = { Text(media.displayName) },
        supportingContent = {
            val duration = media.durationMs?.let { formatClock(it / 1000.0) }
            val sub = if (media.subtitleCount > 0) "有字幕" else "无字幕"
            Text(
                text = listOfNotNull(duration, sub, if (media.isAudio) "音频" else null).joinToString(" · "),
                color = OnDarkMuted,
            )
        },
        trailingContent = {
            if (media.subtitleCount > 0) {
                Icon(Icons.Outlined.Subtitles, contentDescription = "检测到字幕", tint = AccentPurple)
            }
        },
    )
}

@Composable
private fun EmptyLibraryHint() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "请把电脑上的视频和同目录字幕拷到手机，然后选择该文件夹。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("同一目录示例：", style = MaterialTheme.typography.bodyMedium, color = OnDarkMuted)
        Text(
            text = "课程名.mp4\n课程名_中文.srt\n课程名_英文.srt\n课程名_同步.srt",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
