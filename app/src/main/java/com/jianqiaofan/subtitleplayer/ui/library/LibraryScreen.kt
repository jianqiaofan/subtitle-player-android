package com.jianqiaofan.subtitleplayer.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.domain.model.MediaEntry
import com.jianqiaofan.subtitleplayer.domain.model.RecentFolder
import com.jianqiaofan.subtitleplayer.domain.model.visibleLabel
import com.jianqiaofan.subtitleplayer.domain.playback.formatLastWatched
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import com.jianqiaofan.subtitleplayer.ui.OpenFileMenuButton
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenMedia: (uri: String, name: String) -> Unit,
    onBrowseMedia: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenAccount: () -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: LibraryViewModel = viewModel(viewModelStoreOwner = activity)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuFolder by remember { mutableStateOf<RecentFolder?>(null) }
    var remarkFolder by remember { mutableStateOf<RecentFolder?>(null) }
    var remarkText by remember { mutableStateOf("") }
    var deleteFolder by remember { mutableStateOf<RecentFolder?>(null) }
    var showScreenshotManage by remember { mutableStateOf(false) }

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
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回视频")
                        }
                    }
                },
                actions = {
                    OpenFileMenuButton(
                        recents = state.recentMedia,
                        onOpenPicker = onBrowseMedia,
                        onOpenRecent = { item -> onOpenMedia(item.uri, item.displayName) },
                    )
                    TextButton(onClick = onChooseFolder) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text("选择文件夹", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { showScreenshotManage = true }) { Text("截图管理") }
                    TextButton(onClick = onOpenAccount) { Text("账号") }
                    var confirmExit by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirmExit = true }) { Text("退出") }
                    if (confirmExit) {
                        AlertDialog(
                            onDismissRequest = { confirmExit = false },
                            title = { Text("退出") },
                            text = { Text("确定要退出当前字幕播放器应用程序吗？") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmExit = false
                                    onExit()
                                }) { Text("是") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmExit = false }) { Text("否") }
                            },
                        )
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
                            val selected = folder.treeUri == state.currentTreeUri
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) AccentPurple.copy(alpha = 0.28f) else SurfacePanel,
                                modifier = Modifier.combinedClickable(
                                    onClick = { viewModel.onRecentClicked(folder) },
                                    onLongClick = { menuFolder = folder },
                                ),
                            ) {
                                Text(
                                    text = folder.visibleLabel(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    color = if (selected) AccentPurple else MaterialTheme.colorScheme.onSurface,
                                )
                            }
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
                MediaRow(
                    media = media,
                    onClick = { viewModel.rememberAndOpen(media, onOpenMedia) },
                )
            }
        }
    }

    val menu = menuFolder
    if (menu != null) {
        AlertDialog(
            onDismissRequest = { menuFolder = null },
            title = { Text(menu.visibleLabel()) },
            text = { Text("备注只改变这里的显示名称，不会修改文件夹本身的名字。") },
            confirmButton = {
                TextButton(onClick = {
                    remarkFolder = menu
                    remarkText = menu.visibleLabel()
                    menuFolder = null
                }) { Text("添加备注") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        deleteFolder = menu
                        menuFolder = null
                    }) { Text("删除") }
                    TextButton(onClick = { menuFolder = null }) { Text("取消") }
                }
            },
        )
    }
    val editing = remarkFolder
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { remarkFolder = null },
            title = { Text("添加备注") },
            text = {
                OutlinedTextField(
                    value = remarkText,
                    onValueChange = { remarkText = it },
                    singleLine = true,
                    label = { Text("标签显示") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setFolderRemark(editing.treeUri, remarkText)
                    remarkFolder = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { remarkFolder = null }) { Text("取消") }
            },
        )
    }
    val deleting = deleteFolder
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text("删除标签") },
            text = {
                Text("从最近文件夹中删除「${deleting.visibleLabel()}」？已写过的备注会一起删除，文件夹本身不会被修改。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRecentFolder(deleting.treeUri)
                    deleteFolder = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolder = null }) { Text("取消") }
            },
        )
    }

    if (showScreenshotManage) {
        com.jianqiaofan.subtitleplayer.ui.player.ScreenshotManageFlow(
            visible = true,
            onDismiss = { showScreenshotManage = false },
        )
    }
}

@Composable
private fun MediaRow(media: MediaEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = SurfacePanel),
        headlineContent = {
            Column {
                Text(media.displayName)
                val watched = media.watchProgressText()
                if (watched != null) {
                    Text(
                        text = watched,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnDarkMuted,
                    )
                }
            }
        },
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

private fun MediaEntry.watchProgressText(now: Long = System.currentTimeMillis()): String? {
    val time = lastLeftAt?.takeIf { it > 0L }?.let { formatLastWatched(it, now) }
    val percent = playedPercent?.let { "已看 $it%" }
    val line = listOfNotNull(time, percent).joinToString(" · ")
    return line.ifBlank { null }
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
