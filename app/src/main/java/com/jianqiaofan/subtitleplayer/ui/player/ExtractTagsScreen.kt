package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.OpenPersistentTree
import com.jianqiaofan.subtitleplayer.data.TagDocuments
import com.jianqiaofan.subtitleplayer.domain.tags.ExtractSkip
import com.jianqiaofan.subtitleplayer.domain.tags.ExtractSourceFile
import com.jianqiaofan.subtitleplayer.domain.tags.ExtractWrite
import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.describeExtractResults
import com.jianqiaofan.subtitleplayer.domain.tags.extractSourceInsideDestination
import com.jianqiaofan.subtitleplayer.domain.tags.parseTagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.planTagExtract
import com.jianqiaofan.subtitleplayer.domain.tags.sameExtractFolder
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

private const val EXTRACT_TREE_DEPTH = 64

data class ExtractUiState(
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val destUri: String? = null,
    val destName: String? = null,
    val running: Boolean = false,
    val report: String? = null,
)

class ExtractTagsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val library = MediaLibrary(application)
    private val tags = TagDocuments(application)
    private val _state = MutableStateFlow(ExtractUiState())
    val state: StateFlow<ExtractUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { restore() }
    }

    fun onSourcePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            if (!library.persistTreePermission(uri)) {
                _state.update { it.copy(report = "无法保持来源文件夹的访问权限") }
                return@launch
            }
            _state.update { it.copy(sourceUri = uri.toString(), sourceName = library.folderDisplayName(uri)) }
            prefs.rememberTagExtract(uri.toString(), _state.value.destUri)
        }
    }

    fun onDestPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            if (!library.persistTreePermission(uri)) {
                _state.update { it.copy(report = "无法保持保存位置的访问权限") }
                return@launch
            }
            _state.update { it.copy(destUri = uri.toString(), destName = library.folderDisplayName(uri)) }
            prefs.rememberTagExtract(_state.value.sourceUri, uri.toString())
        }
    }

    fun consumeReport() {
        _state.update { it.copy(report = null) }
    }

    fun start(onApplied: () -> Unit) {
        val sourceRaw = _state.value.sourceUri
        val destRaw = _state.value.destUri
        if (sourceRaw.isNullOrBlank() || destRaw.isNullOrBlank()) {
            _state.update { it.copy(report = "请选择来源文件夹和保存位置") }
            return
        }
        val source = Uri.parse(sourceRaw)
        val dest = Uri.parse(destRaw)
        val sourceTreeId = treeId(source)
        val destTreeId = treeId(dest)
        if (sourceTreeId == null || destTreeId == null) {
            _state.update { it.copy(report = "无法读取所选文件夹") }
            return
        }
        if (sameExtractFolder(sourceTreeId, destTreeId)) {
            _state.update { it.copy(report = "来源文件夹和保存位置不能是同一个文件夹") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            val message = withContext(Dispatchers.IO) { extract(source, dest, sourceTreeId, destTreeId) }
            _state.update { it.copy(running = false, report = message) }
            onApplied()
        }
    }

    private fun extract(source: Uri, dest: Uri, sourceTreeId: String, destTreeId: String): String {
        val files = library.listTreeFiles(source, EXTRACT_TREE_DEPTH)
        val sources = files.mapNotNull { file ->
            if (file.isDirectory || !file.name.endsWith(".tags.json", ignoreCase = true)) return@mapNotNull null
            val documentId = try {
                DocumentsContract.getDocumentId(file.documentUri)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (extractSourceInsideDestination(documentId, sourceTreeId, destTreeId)) return@mapNotNull null
            val label = if (file.relativeDir.isEmpty()) file.name else "${file.relativeDir}/${file.name}"
            ExtractSourceFile(label, file.name, tags.readText(file.documentUri))
        }
        val destRoot = library.treeDocumentUri(dest)
        val destId = try {
            DocumentsContract.getDocumentId(destRoot)
        } catch (_: Exception) {
            return "无法读取保存位置"
        }
        val existingNames = linkedMapOf<String, String>()
        val existingDocs = linkedMapOf<String, TagDocument>()
        val unreadable = mutableSetOf<String>()
        val destChildren = linkedMapOf<String, Uri>()
        for (child in library.listFolder(dest, destId)) {
            if (child.isDirectory || !child.displayName.endsWith(".tags.json", ignoreCase = true)) continue
            val key = child.displayName.lowercase()
            existingNames[key] = child.displayName
            destChildren[key] = child.documentUri
            val text = tags.readText(child.documentUri)
            val parsed = text?.let { parseTagDocument(it) }
            if (text == null || parsed == null) unreadable += key else existingDocs[key] = parsed
        }
        val plan = planTagExtract(sources, existingDocs, existingNames, unreadable)
        val written = mutableListOf<ExtractWrite>()
        val writeSkips = mutableListOf<ExtractSkip>()
        for (write in plan.writes) {
            val existing = destChildren[write.fileName.lowercase()]
            val result = if (existing == null) {
                tags.createAndWrite(destRoot, write.fileName, write.document)
            } else {
                tags.writeDocument(existing, write.document)
            }
            if (result.isSuccess) written += write else writeSkips += ExtractSkip(write.fileName, "无法写入")
        }
        return describeExtractResults(plan.foundAny, written, plan.skips + writeSkips)
    }

    private suspend fun restore() {
        val source = usableTree(prefs.tagExtractSourceOnce())
        val dest = usableTree(prefs.tagExtractDestOnce())
        _state.update {
            it.copy(
                sourceUri = source?.toString(),
                sourceName = source?.let(library::folderDisplayName),
                destUri = dest?.toString(),
                destName = dest?.let(library::folderDisplayName),
            )
        }
    }

    private fun usableTree(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val uri = Uri.parse(raw)
        if (!library.hasPersistedAccess(uri)) return null
        return uri
    }

    private fun treeId(uri: Uri): String? =
        try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractTagsScreen(
    onBack: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: ExtractTagsViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourcePicker = rememberLauncherForActivityResult(OpenPersistentTree()) { viewModel.onSourcePicked(it) }
    val destPicker = rememberLauncherForActivityResult(OpenPersistentTree()) { viewModel.onDestPicked(it) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        topBar = {
            TopAppBar(
                title = { Text("提取全部标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = SurfacePanel),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "这个功能把这台设备上分散在各个文件夹里的标签，集中复制到一个地方。然后把那个文件夹拷到另一台设备，再用「批量同步标签」写回视频旁边。",
                color = OnDark,
            )
            Text("「来源文件夹」选这台设备上存放视频和字幕的目录。子文件夹里的标签也会一起查找。", color = OnDark)
            Text("「保存到」另选一个文件夹，例如 U 盘，或一个新建的空文件夹。标签会平铺放在这里，不再按原来的子目录分开放。", color = OnDark)
            Text("点「开始提取」。来源文件夹里的原文件不会被修改，字幕正文也不会被改动。", color = OnDark)
            Text("到另一台设备后，打开「批量同步标签」，选择这里面的标签文件，再选择那台设备上的视频文件夹。", color = OnDark)
            Text(
                "不同子文件夹里如果有同名字幕，例如都叫 lesson_中文.srt，它们的标签会合并成一个文件：对得上的句子合并标签和备注，对不上的句子会保留。保存位置里如果已经有同名标签，也会合并进去，不会覆盖。",
                color = OnDark,
            )
            TextButton(onClick = { sourcePicker.launch(state.sourceUri?.let(Uri::parse)) }) {
                Text("来源文件夹：${state.sourceName ?: "未选择"}")
            }
            TextButton(onClick = { destPicker.launch(state.destUri?.let(Uri::parse)) }) {
                Text("保存到：${state.destName ?: "未选择"}")
            }
            TextButton(
                onClick = { viewModel.start(onApplied) },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始提取", color = AccentPurple) }
            if (state.running) CircularProgressIndicator(color = AccentPurple)
        }
    }
    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::consumeReport,
            title = { Text("提取结果") },
            text = {
                Text(
                    report,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::consumeReport) { Text("关闭") } },
        )
    }
}
