package com.jianqiaofan.subtitleplayer.ui.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jianqiaofan.subtitleplayer.data.ManagedScreenshot
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.domain.screenshot.ManagePagingMode
import com.jianqiaofan.subtitleplayer.domain.screenshot.formatScreenshotStamp
import com.jianqiaofan.subtitleplayer.domain.screenshot.groupManagedIndicesByPath
import com.jianqiaofan.subtitleplayer.domain.screenshot.managePageCount
import com.jianqiaofan.subtitleplayer.domain.screenshot.managePageIndices
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotDisplayTitle
import com.jianqiaofan.subtitleplayer.domain.model.visibleLabel
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

@Composable
fun ScreenshotManageDialog(
    viewModel: ScreenshotManageViewModel,
    onDismiss: () -> Unit,
    onView: (items: List<ManagedScreenshot>, index: Int) -> Unit,
    currentVideoOnly: Boolean = false,
    onRefreshCurrentVideo: (() -> Unit)? = null,
) {
    val manage by viewModel.manageState.collectAsStateWithLifecycle()
    var recentMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentVideoOnly) {
        if (!currentVideoOnly) viewModel.prepare()
    }

    val picker = rememberLauncherForActivityResult(OpenPersistentTree()) { uri ->
        if (uri != null) viewModel.selectFolder(uri)
    }

    val pathGroups = remember(manage.items) { groupManagedIndicesByPath(manage.items.map { it.relativeDir }) }
    val paging = if (currentVideoOnly) ManagePagingMode.All else manage.paging
    val pageCount = managePageCount(paging, pathGroups)
    val safePage = manage.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pageRows = managePageIndices(paging, pathGroups, manage.items.size, safePage)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            color = WindowBackground,
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截图管理", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (currentVideoOnly) onRefreshCurrentVideo?.invoke()
                            else viewModel.refreshFolder()
                        },
                        enabled = if (currentVideoOnly) {
                            onRefreshCurrentVideo != null && !manage.scanning
                        } else {
                            !manage.folderUri.isNullOrBlank() && !manage.scanning
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
                if (currentVideoOnly) {
                    Text(
                        text = "当前视频：${manage.folderLabel.ifBlank { "—" }}",
                        color = Color(0xFFDDDDDD),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("文件夹", color = Color(0xFFDDDDDD), fontSize = 13.sp)
                        OutlinedTextField(
                            value = manage.folderLabel.ifBlank { manage.folderUri.orEmpty() },
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { picker.launch(null) }) { Text("选择文件夹") }
                        Box {
                            TextButton(
                                onClick = { recentMenu = true },
                                enabled = manage.recents.isNotEmpty(),
                            ) { Text("最近打开") }
                            DropdownMenu(expanded = recentMenu, onDismissRequest = { recentMenu = false }) {
                                if (manage.recents.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("暂无最近打开的文件夹") },
                                        onClick = { recentMenu = false },
                                        enabled = false,
                                    )
                                } else {
                                    manage.recents.forEach { folder ->
                                        DropdownMenuItem(
                                            text = { Text(folder.visibleLabel()) },
                                            onClick = {
                                                recentMenu = false
                                                viewModel.selectFolder(
                                                    Uri.parse(folder.treeUri),
                                                    folder.displayName,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        manage.scanning -> "正在扫描…"
                        currentVideoOnly && manage.items.isNotEmpty() ->
                            "当前视频共 ${manage.items.size} 个截图。"
                        currentVideoOnly -> "当前视频还没有截图。"
                        manage.items.isNotEmpty() ->
                            "共找到 ${manage.items.size} 个截图（不含截屏保存导出的普通图片）。"
                        else -> "需要有对应视频且 screenshots.json 有登记。截屏保存导出的普通图片不在此列。"
                    },
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                ManageTableHeader()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(SurfacePanel, RoundedCornerShape(4.dp)),
                ) {
                    itemsIndexed(pageRows) { pageLocalIndex, globalIndex ->
                        val item = manage.items[globalIndex]
                        val selected = manage.selectedRow == globalIndex
                        ManageTableRow(
                            index = globalIndex + 1,
                            item = item,
                            selected = selected,
                            onSelect = { viewModel.setSelectedRow(globalIndex) },
                            onView = {
                                val pageItems = pageRows.map { manage.items[it] }
                                onView(pageItems, pageLocalIndex)
                            },
                        )
                    }
                }
                if (!currentVideoOnly) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分页", color = Color.White, fontSize = 13.sp)
                        RadioButton(
                            selected = manage.paging == ManagePagingMode.All,
                            onClick = { viewModel.setPaging(ManagePagingMode.All) },
                        )
                        Text(
                            if (manage.paging == ManagePagingMode.All) "全部 ${manage.items.size} 条" else "全部",
                            color = Color.White,
                            modifier = Modifier.clickable { viewModel.setPaging(ManagePagingMode.All) },
                        )
                        RadioButton(
                            selected = manage.paging == ManagePagingMode.ByPath,
                            onClick = { viewModel.setPaging(ManagePagingMode.ByPath) },
                        )
                        Text(
                            "按路径",
                            color = Color.White,
                            modifier = Modifier.clickable { viewModel.setPaging(ManagePagingMode.ByPath) },
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.setPageIndex((safePage - 1).coerceAtLeast(0)) },
                            enabled = safePage > 0 && manage.paging == ManagePagingMode.ByPath,
                        ) { Text("上一页") }
                        Text(
                            "第 ${if (pageCount == 0) 0 else safePage + 1}/$pageCount 页",
                            color = Color.White,
                        )
                        TextButton(
                            onClick = {
                                viewModel.setPageIndex((safePage + 1).coerceAtMost(pageCount - 1))
                            },
                            enabled = safePage < pageCount - 1 && manage.paging == ManagePagingMode.ByPath,
                        ) { Text("下一页") }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "全部 ${manage.items.size} 条",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageTableHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Color(0xFF333333))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("序号", 44)
        HeaderCell("截图标题", 120)
        HeaderCell("路径", 220)
        HeaderCell("笔记", 44)
        HeaderCell("创建时间", 140)
        HeaderCell("修改时间", 140)
        HeaderCell("查看", 48)
    }
}

@Composable
private fun ManageTableRow(
    index: Int,
    item: ManagedScreenshot,
    selected: Boolean,
    onSelect: () -> Unit,
    onView: () -> Unit,
) {
    val bg = if (selected) Color(0xFF3A2458) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyCell(index.toString(), 44, TextAlign.Center)
        BodyCell(screenshotDisplayTitle(item.shot), 120)
        BodyCell(item.relativePath, 220)
        BodyCell(item.shot.notes.size.toString(), 44, TextAlign.Center)
        BodyCell(formatScreenshotStamp(item.createdAt).ifBlank { "—" }, 140)
        BodyCell(formatScreenshotStamp(item.updatedAt).ifBlank { "—" }, 140)
        Box(
            Modifier
                .width(48.dp)
                .heightIn(min = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onView) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = "查看",
                    tint = Color(0xFFE2C6FF),
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, widthDp: Int) {
    Text(
        text = text,
        color = Color(0xFFCCCCCC),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(widthDp.dp),
        maxLines = 1,
    )
}

@Composable
private fun BodyCell(text: String, widthDp: Int, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(widthDp.dp),
    )
}
