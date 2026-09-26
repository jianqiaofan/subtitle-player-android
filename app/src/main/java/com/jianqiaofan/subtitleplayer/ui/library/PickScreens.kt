package com.jianqiaofan.subtitleplayer.ui.library

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.FolderChild
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.OpenMediaDocument
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.domain.model.RecentFolder
import com.jianqiaofan.subtitleplayer.domain.model.isMediaFile
import com.jianqiaofan.subtitleplayer.domain.model.visibleLabel
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowserCrumb(
    val treeUri: String,
    val documentId: String,
    val title: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowserScreen(
    onBack: () -> Unit,
    onOpen: (uri: String, name: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val library = remember { MediaLibrary(app) }
    val prefs = remember { AppPreferences(app) }
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<RecentFolder>>(emptyList()) }
    var atRoots by remember { mutableStateOf(true) }
    var crumbs by remember { mutableStateOf<List<BrowserCrumb>>(emptyList()) }
    var entries by remember { mutableStateOf<List<FolderChild>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun showFolder(treeUri: String, documentId: String) {
        loading = true
        error = null
        val children = withContext(Dispatchers.IO) {
            library.listFolder(Uri.parse(treeUri), documentId)
        }
        entries = children
            .filter { it.isDirectory || isMediaFile(it.displayName) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.displayName.lowercase() }))
        loading = false
    }

    fun openCrumb(crumb: BrowserCrumb, replace: Boolean) {
        scope.launch {
            atRoots = false
            crumbs = if (replace && crumbs.isNotEmpty()) {
                crumbs.dropLast(1) + crumb
            } else {
                crumbs + crumb
            }
            showFolder(crumb.treeUri, crumb.documentId)
        }
    }

    LaunchedEffect(Unit) {
        val recent = prefs.recentFolders.first()
        val current = prefs.currentTreeUriOnce()
        roots = recent
        val start = recent.firstOrNull { it.treeUri == current } ?: recent.singleOrNull()
        if (start == null) {
            atRoots = true
            loading = false
        } else {
            val tree = Uri.parse(start.treeUri)
            val documentId = DocumentsContract.getTreeDocumentId(tree)
            atRoots = false
            crumbs = listOf(BrowserCrumb(start.treeUri, documentId, start.visibleLabel()))
            showFolder(start.treeUri, documentId)
        }
    }

    val systemPicker = rememberLauncherForActivityResult(OpenMediaDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            library.persistReadPermission(uri)
            val name = withContext(Dispatchers.IO) { library.displayNameOf(uri) } ?: "媒体"
            onOpen(uri.toString(), name)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = WindowBackground,
        topBar = {
            TopAppBar(
                title = { Text(crumbs.lastOrNull()?.title ?: "打开媒体") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!atRoots) {
                        TextButton(
                            onClick = {
                                if (crumbs.size <= 1) {
                                    atRoots = true
                                    crumbs = emptyList()
                                    entries = emptyList()
                                    error = null
                                } else {
                                    val parent = crumbs[crumbs.size - 2]
                                    crumbs = crumbs.dropLast(1)
                                    scope.launch { showFolder(parent.treeUri, parent.documentId) }
                                }
                            },
                        ) { Text("上一级") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfacePanel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                item { CircularProgressIndicator(color = AccentPurple, modifier = Modifier.padding(24.dp)) }
            }
            error?.let { message ->
                item { Text(message, color = OnDarkMuted) }
            }
            if (!loading && atRoots) {
                if (roots.isEmpty()) {
                    item {
                        Text(
                            "还没有授权过的文件夹。请返回后用「选择文件夹」授权，再来打开视频。",
                            color = OnDarkMuted,
                        )
                    }
                } else {
                    items(roots, key = { it.treeUri }) { folder ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val tree = Uri.parse(folder.treeUri)
                                val documentId = DocumentsContract.getTreeDocumentId(tree)
                                openCrumb(
                                    BrowserCrumb(folder.treeUri, documentId, folder.visibleLabel()),
                                    replace = false,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = SurfacePanel),
                            leadingContent = {
                                Icon(Icons.Outlined.Folder, contentDescription = null, tint = AccentPurple)
                            },
                            headlineContent = { Text(folder.visibleLabel()) },
                        )
                    }
                }
            }
            if (!loading && !atRoots) {
                items(entries, key = { it.documentUri.toString() }) { child ->
                    val media = isMediaFile(child.displayName)
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (child.isDirectory) {
                                val treeUri = crumbs.last().treeUri
                                openCrumb(
                                    BrowserCrumb(treeUri, DocumentsContract.getDocumentId(child.documentUri), child.displayName),
                                    replace = false,
                                )
                            } else if (media) {
                                onOpen(child.documentUri.toString(), child.displayName)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = SurfacePanel),
                        leadingContent = {
                            Icon(
                                imageVector = if (child.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = AccentPurple,
                            )
                        },
                        headlineContent = { Text(child.displayName) },
                    )
                }
                if (entries.isEmpty()) {
                    item { Text("这个文件夹里没有视频或子文件夹。", color = OnDarkMuted) }
                }
            }
            item {
                TextButton(
                    onClick = { systemPicker.launch(crumbs.lastOrNull()?.let { Uri.parse(it.treeUri) }) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("从系统文件中选择") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: LibraryViewModel = viewModel(viewModelStoreOwner = activity)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(OpenPersistentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onFolderPicked(uri)
        onBack()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = WindowBackground,
        topBar = {
            TopAppBar(
                title = { Text("选择文件夹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfacePanel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "点一个最近文件夹即可打开。授权新文件夹会进入系统的文件夹选择。",
                    color = OnDarkMuted,
                )
            }
            item {
                TextButton(onClick = {
                    val initial = state.currentTreeUri?.let { Uri.parse(it) }
                    picker.launch(initial)
                }) { Text("授权新文件夹") }
            }
            items(state.recents, key = { it.treeUri }) { folder ->
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.onRecentClicked(folder)
                        onBack()
                    },
                    colors = ListItemDefaults.colors(containerColor = SurfacePanel),
                    leadingContent = {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = AccentPurple)
                    },
                    headlineContent = { Text(folder.visibleLabel()) },
                )
            }
        }
    }
}
