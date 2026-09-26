package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.data.OpenTagDocuments
import com.jianqiaofan.subtitleplayer.data.TagDocuments
import com.jianqiaofan.subtitleplayer.data.TreeFile
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION
import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.describeSyncResults
import com.jianqiaofan.subtitleplayer.domain.tags.matchBatchTargets
import com.jianqiaofan.subtitleplayer.domain.tags.parseTagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.planTagSync
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleFileNameFromTagFile
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDark
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BatchSyncRow(
    val key: String,
    val videoFileName: String,
    val pathLabel: String,
    val tagFileName: String,
    val subtitleFileName: String,
    val hasExistingTag: Boolean,
    val sourceUri: String,
    val parentDocumentUri: String,
    val existingTagUri: String?,
    val checked: Boolean = false,
)

data class BatchSyncUiState(
    val tagFiles: List<RecentMedia> = emptyList(),
    val videoDirUri: String? = null,
    val videoDirName: String? = null,
    val rows: List<BatchSyncRow> = emptyList(),
    val scanning: Boolean = false,
    val report: String? = null,
    val detail: String? = null,
)

class BatchTagSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val library = MediaLibrary(application)
    private val tags = TagDocuments(application)
    private val _state = MutableStateFlow(BatchSyncUiState())
    val state: StateFlow<BatchSyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restore() }
    }

    fun onTagFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { library.persistReadPermission(it) }
            val files = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    RecentMedia(uri.toString(), library.displayNameOf(uri) ?: "标签文件", "")
                }.filter { subtitleFileNameFromTagFile(it.displayName) != null }
            }
            _state.update { it.copy(tagFiles = files) }
            prefs.rememberBatchTagSync(files, _state.value.videoDirUri)
            scan()
        }
    }

    fun onVideoDirPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            if (!library.persistTreePermission(uri)) {
                _state.update { it.copy(report = "无法保持该文件夹的访问权限") }
                return@launch
            }
            val name = library.folderDisplayName(uri)
            _state.update { it.copy(videoDirUri = uri.toString(), videoDirName = name) }
            prefs.rememberBatchTagSync(_state.value.tagFiles, uri.toString())
            scan()
        }
    }

    fun toggle(key: String) {
        _state.update { state ->
            state.copy(rows = state.rows.map { if (it.key == key) it.copy(checked = !it.checked) else it })
        }
    }

    fun selectAll() = setChecks { true }
    fun selectNone() = setChecks { false }
    fun invert() = setChecks { !it }

    fun apply(onApplied: () -> Unit) {
        val chosen = _state.value.rows.filter { it.checked }
        if (chosen.isEmpty()) {
            _state.update { it.copy(report = "请先勾选要同步的视频") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val results = withContext(Dispatchers.IO) {
                chosen.map { row -> applyRow(row) }
            }
            _state.update { it.copy(scanning = false, report = describeSyncResults(results)) }
            onApplied()
            scan()
        }
    }

    fun consumeReport() {
        _state.update { it.copy(report = null) }
    }

    fun showDetail(text: String) {
        _state.update { it.copy(detail = text) }
    }

    fun consumeDetail() {
        _state.update { it.copy(detail = null) }
    }

    private fun setChecks(transform: (Boolean) -> Boolean) {
        _state.update { state -> state.copy(rows = state.rows.map { it.copy(checked = transform(it.checked)) }) }
    }

    private suspend fun restore() {
        val files = prefs.batchTagSyncFilesOnce().filter { library.documentExists(Uri.parse(it.uri)) }
        val dir = prefs.batchTagSyncVideoDirOnce()?.takeIf { library.hasPersistedAccess(Uri.parse(it)) }
        val name = dir?.let { library.folderDisplayName(Uri.parse(it)) }
        _state.update { it.copy(tagFiles = files, videoDirUri = dir, videoDirName = name) }
        if (files.isNotEmpty() && dir != null) scan()
    }

    private suspend fun scan() {
        val dir = _state.value.videoDirUri ?: return
        val files = _state.value.tagFiles
        if (files.isEmpty()) {
            _state.update { it.copy(rows = emptyList()) }
            return
        }
        _state.update { it.copy(scanning = true) }
        val rows = withContext(Dispatchers.IO) {
            val tree = library.listTreeFiles(Uri.parse(dir))
            val byDir = tree.filter { !it.isDirectory }.groupBy { it.relativeDir }.mapValues { entry -> entry.value.map { it.name } }
            files.flatMap { source -> rowsForSource(source, tree, byDir) }
        }
        _state.update { it.copy(scanning = false, rows = rows) }
    }

    private fun rowsForSource(
        source: RecentMedia,
        tree: List<TreeFile>,
        byDir: Map<String, List<String>>,
    ): List<BatchSyncRow> {
        val subtitleName = subtitleFileNameFromTagFile(source.displayName) ?: return emptyList()
        val text = tags.readText(Uri.parse(source.uri)) ?: return emptyList()
        val document = parseTagDocument(text) ?: return emptyList()
        if (document.subtitleFile != subtitleName || document.entries.isEmpty()) return emptyList()
        return matchBatchTargets(source.displayName, document, byDir).mapNotNull { match ->
            val video = tree.firstOrNull {
                !it.isDirectory && it.relativeDir == match.relativeDir && it.name == match.videoFileName
            } ?: return@mapNotNull null
            val existing = tree.firstOrNull {
                !it.isDirectory && it.relativeDir == match.relativeDir && it.name == tagFileNameFor(match.subtitleFileName)
            }
            if (existing != null && library.sameDocument(Uri.parse(source.uri), existing.documentUri)) {
                return@mapNotNull null
            }
            val path = if (match.relativeDir.isEmpty()) match.videoFileName else "${match.relativeDir}/${match.videoFileName}"
            BatchSyncRow(
                key = "${source.uri}|$path",
                videoFileName = match.videoFileName,
                pathLabel = path,
                tagFileName = source.displayName,
                subtitleFileName = match.subtitleFileName,
                hasExistingTag = match.hasExistingTag,
                sourceUri = source.uri,
                parentDocumentUri = video.parentDocumentUri.toString(),
                existingTagUri = existing?.documentUri?.toString(),
            )
        }
    }

    private fun applyRow(row: BatchSyncRow): com.jianqiaofan.subtitleplayer.domain.tags.TagSyncDecision {
        val source = Uri.parse(row.sourceUri)
        val parsed = tags.readText(source)?.let { parseTagDocument(it) }
        val existingDoc = row.existingTagUri?.let { uri ->
            tags.readDocument(Uri.parse(uri)) ?: TagDocument(TAG_DOCUMENT_VERSION, row.subtitleFileName, emptyList())
        }
        val decision = planTagSync(
            tagFileName = row.tagFileName,
            parsed = parsed,
            destinationSubtitleExists = true,
            destinationTag = existingDoc,
            sourceIsDestination = false,
        )
        val document = decision.document ?: return decision
        val written = if (row.existingTagUri == null) {
            tags.createAndWrite(Uri.parse(row.parentDocumentUri), tagFileNameFor(row.subtitleFileName), document)
        } else {
            tags.writeDocument(Uri.parse(row.existingTagUri), document)
        }
        return if (written.isSuccess) {
            decision
        } else {
            decision.copy(kind = com.jianqiaofan.subtitleplayer.domain.tags.TagSyncKind.Skipped, reason = "无法写入 ${row.tagFileName}", document = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchTagSyncScreen(
    onBack: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: BatchTagSyncViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tagPicker = rememberLauncherForActivityResult(OpenTagDocuments()) { viewModel.onTagFilesPicked(it) }
    val dirPicker = rememberLauncherForActivityResult(OpenPersistentTree()) { viewModel.onVideoDirPicked(it) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        topBar = {
            TopAppBar(
                title = { Text("批量同步标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = SurfacePanel),
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "选择标签文件，再选择接收它们的视频文件夹（包含子文件夹）。没有标签文件就复制，已有则合并，不覆盖原有标签，也不改字幕正文。",
                color = OnDark,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val initial = state.tagFiles.firstOrNull()?.uri?.let(Uri::parse)
                    tagPicker.launch(initial)
                }) { Text("选择标签文件") }
                TextButton(onClick = {
                    dirPicker.launch(state.videoDirUri?.let(Uri::parse))
                }) { Text("选择视频文件夹") }
            }
            Text("标签 ${state.tagFiles.size} 个 · 文件夹 ${state.videoDirName ?: "未选择"}", color = AccentPurple)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::selectAll) { Text("全选") }
                TextButton(onClick = viewModel::selectNone) { Text("全不选") }
                TextButton(onClick = viewModel::invert) { Text("反选") }
                TextButton(onClick = { viewModel.apply(onApplied) }, enabled = !state.scanning) { Text("同步所选") }
            }
            if (state.scanning) CircularProgressIndicator(color = AccentPurple)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.rows, key = { it.key }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.showDetail("${row.videoFileName}\n${row.pathLabel}\n${row.tagFileName}") },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = row.checked,
                            onCheckedChange = { viewModel.toggle(row.key) },
                            colors = CheckboxDefaults.colors(
                                uncheckedColor = Color(0xFFDDDDDD),
                                checkedColor = AccentPurple,
                            ),
                        )
                        Text(row.videoFileName, Modifier.weight(1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.pathLabel, Modifier.weight(1.3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.tagFileName, Modifier.weight(1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (row.hasExistingTag) "合并" else "复制", Modifier.weight(0.6f), maxLines = 1)
                    }
                }
            }
        }
    }
    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::consumeReport,
            title = { Text("同步结果") },
            text = { Text(report) },
            confirmButton = { TextButton(onClick = viewModel::consumeReport) { Text("关闭") } },
        )
    }
    state.detail?.let { detail ->
        AlertDialog(
            onDismissRequest = viewModel::consumeDetail,
            text = { Text(detail) },
            confirmButton = { TextButton(onClick = viewModel::consumeDetail) { Text("关闭") } },
        )
    }
}
