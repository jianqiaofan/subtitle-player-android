package com.jianqiaofan.subtitleplayer.ui.cloud

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.data.OpenTagDocuments
import com.jianqiaofan.subtitleplayer.domain.cloud.LibrarySubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.LibraryTag
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLibraryScreen(onBack: () -> Unit) {
    val viewModel: CloudLibraryViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val subtitlePicker = rememberLauncherForActivityResult(OpenTagDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadSubtitles(uris, batch = uris.size > 1)
    }
    val folderPicker = rememberLauncherForActivityResult(OpenPersistentTree()) { uri: Uri? ->
        if (uri != null) viewModel.uploadSubtitleFolder(uri)
    }
    val tagPicker = rememberLauncherForActivityResult(OpenTagDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadTags(uris)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }
    Scaffold(
        containerColor = WindowBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("云端管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = viewModel::refresh) { Text("刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfacePanel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = AccentPurple,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = if (state.tab == CloudLibraryTab.Subtitles) 0 else 1, containerColor = SurfacePanel) {
                Tab(
                    selected = state.tab == CloudLibraryTab.Subtitles,
                    onClick = { viewModel.selectTab(CloudLibraryTab.Subtitles) },
                    text = { Text("字幕管理") },
                )
                Tab(
                    selected = state.tab == CloudLibraryTab.Tags,
                    onClick = { viewModel.selectTab(CloudLibraryTab.Tags) },
                    text = { Text("标签管理") },
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::viewCurrent) { Text("查看") }
                TextButton(onClick = viewModel::requestDelete) { Text("删除") }
                if (state.tab == CloudLibraryTab.Subtitles) {
                    TextButton(onClick = viewModel::onShareClick) { Text(viewModel.shareButtonLabel()) }
                    TextButton(onClick = { subtitlePicker.launch(null) }) { Text("上传字幕") }
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("批量上传") }
                } else {
                    TextButton(onClick = { tagPicker.launch(null) }) { Text("上传标签") }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(24.dp), color = AccentPurple)
            } else if (state.tab == CloudLibraryTab.Subtitles) {
                HeaderRow(
                    allChecked = state.subtitles.isNotEmpty() && state.checkedSubtitles.containsAll(state.subtitles.map { it.id }),
                    columns = listOf("字幕文件", "视频", "更新时间", "共享"),
                    onToggleAll = viewModel::toggleAll,
                )
                if (state.subtitles.isEmpty()) {
                    Text("云端还没有字幕。", color = OnDarkMuted, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.subtitles, key = { it.id }) { item ->
                        SubtitleRow(
                            item = item,
                            checked = item.id in state.checkedSubtitles,
                            focused = item.id == state.focusSubtitle,
                            time = viewModel.timeLabel(item.updatedAt),
                            onCheck = { viewModel.toggleSubtitle(item.id) },
                            onFocus = { viewModel.focusSubtitle(item.id) },
                        )
                    }
                }
            } else {
                HeaderRow(
                    allChecked = state.tags.isNotEmpty() && state.checkedTags.containsAll(state.tags.map { it.id }),
                    columns = listOf("字幕文件", "视频", "更新时间"),
                    onToggleAll = viewModel::toggleAll,
                )
                if (state.tags.isEmpty()) {
                    Text("云端还没有标签。", color = OnDarkMuted, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.tags, key = { it.id }) { item ->
                        TagRow(
                            item = item,
                            checked = item.id in state.checkedTags,
                            focused = item.id == state.focusTag,
                            time = viewModel.timeLabel(item.updatedAt),
                            onCheck = { viewModel.toggleTag(item.id) },
                            onFocus = { viewModel.focusTag(item.id) },
                        )
                    }
                }
            }
        }
    }
    state.viewerTitle?.let { title ->
        AlertDialog(
            onDismissRequest = viewModel::closeViewer,
            title = { Text(title) },
            text = {
                Text(
                    state.viewerBody.orEmpty(),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::closeViewer) { Text("关闭") } },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除云端内容") },
            text = { Text("删除后，其他设备也不能再同步这些内容。") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("取消") } },
        )
    }
    if (state.askMixedShare) {
        AlertDialog(
            onDismissRequest = { viewModel.chooseMixedShare(null) },
            title = { Text("共享设置不一致") },
            text = { Text("选中的字幕里，有的已共享，有的仅自己使用。") },
            confirmButton = { TextButton(onClick = { viewModel.chooseMixedShare(true) }) { Text("全部设为共享") } },
            dismissButton = {
                TextButton(onClick = { viewModel.chooseMixedShare(false) }) { Text("全部取消共享") }
            },
        )
    }
    if (state.askShare) {
        AlertDialog(
            onDismissRequest = { viewModel.chooseShare(null) },
            title = { Text("是否共享这些字幕？") },
            text = { Text("共享后，别的用户在没有本地字幕时可以看到。标签不会共享。") },
            confirmButton = { TextButton(onClick = { viewModel.chooseShare(true) }) { Text("共享") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.chooseShare(false) }) { Text("仅自己使用") }
                    TextButton(onClick = { viewModel.chooseShare(null) }) { Text("取消") }
                }
            },
        )
    }
    if (state.askReplace) {
        AlertDialog(
            onDismissRequest = { viewModel.chooseReplace(null) },
            title = { Text("云端已有不同内容") },
            text = { Text("同一种语言的云端字幕和本地文件不一样。") },
            confirmButton = { TextButton(onClick = { viewModel.chooseReplace(false) }) { Text("替换") } },
            dismissButton = {
                Row {
                    if (state.replaceIsBatch) {
                        TextButton(onClick = { viewModel.chooseReplace(true) }) { Text("跳过已有") }
                    }
                    TextButton(onClick = { viewModel.chooseReplace(null) }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun HeaderRow(allChecked: Boolean, columns: List<String>, onToggleAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = allChecked, onCheckedChange = { onToggleAll() })
        columns.forEach { title ->
            Text(title, modifier = Modifier.weight(1f), color = OnDarkMuted, maxLines = 1)
        }
    }
}

@Composable
private fun SubtitleRow(
    item: LibrarySubtitle,
    checked: Boolean,
    focused: Boolean,
    time: String,
    onCheck: () -> Unit,
    onFocus: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocus)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() })
        Text(item.subtitleName, Modifier.weight(1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (focused) AccentPurple else MaterialTheme.colorScheme.onSurface)
        Text(item.videoStem, Modifier.weight(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time, Modifier.weight(1f), maxLines = 1)
        Text(if (item.shared) "共享" else "仅自己", Modifier.weight(0.6f), maxLines = 1)
    }
}

@Composable
private fun TagRow(
    item: LibraryTag,
    checked: Boolean,
    focused: Boolean,
    time: String,
    onCheck: () -> Unit,
    onFocus: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocus)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() })
        Text(item.subtitleName, Modifier.weight(1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (focused) AccentPurple else MaterialTheme.colorScheme.onSurface)
        Text(item.videoStem, Modifier.weight(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time, Modifier.weight(1f), maxLines = 1)
    }
}
