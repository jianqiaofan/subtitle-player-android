package com.jianqiaofan.subtitleplayer.ui.cloud

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianqiaofan.subtitleplayer.data.CloudException
import com.jianqiaofan.subtitleplayer.data.CloudRepository
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.UploadPreparation
import com.jianqiaofan.subtitleplayer.domain.cloud.LibrarySubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.LibraryTag
import com.jianqiaofan.subtitleplayer.domain.cloud.describeCloudTagDocument
import com.jianqiaofan.subtitleplayer.domain.time.formatUtcLocal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CloudLibraryTab { Subtitles, Tags }

data class CloudLibraryUiState(
    val tab: CloudLibraryTab = CloudLibraryTab.Subtitles,
    val loading: Boolean = true,
    val subtitles: List<LibrarySubtitle> = emptyList(),
    val tags: List<LibraryTag> = emptyList(),
    val checkedSubtitles: Set<Long> = emptySet(),
    val checkedTags: Set<Long> = emptySet(),
    val focusSubtitle: Long? = null,
    val focusTag: Long? = null,
    val message: String? = null,
    val viewerTitle: String? = null,
    val viewerBody: String? = null,
    val confirmDelete: Boolean = false,
    val askMixedShare: Boolean = false,
    val askShare: Boolean = false,
    val askReplace: Boolean = false,
    val replaceIsBatch: Boolean = false,
)

class CloudLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CloudRepository(application)
    private val library = MediaLibrary(application)
    private val _state = MutableStateFlow(CloudLibraryUiState())
    val state: StateFlow<CloudLibraryUiState> = _state.asStateFlow()
    private var prepared = UploadPreparation(emptyList(), emptyList())
    private var sharedChoice = false
    private var uploadIsBatch = false

    init {
        refresh()
    }

    fun selectTab(tab: CloudLibraryTab) = _state.update { it.copy(tab = tab) }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val subtitles = repository.listSubtitles()
                val tags = repository.listTags()
                _state.update {
                    it.copy(
                        loading = false,
                        subtitles = subtitles,
                        tags = tags,
                        checkedSubtitles = it.checkedSubtitles.intersect(subtitles.map { item -> item.id }.toSet()),
                        checkedTags = it.checkedTags.intersect(tags.map { item -> item.id }.toSet()),
                    )
                }
            } catch (e: CloudException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    fun focusSubtitle(id: Long) = _state.update { it.copy(focusSubtitle = id) }

    fun focusTag(id: Long) = _state.update { it.copy(focusTag = id) }

    fun toggleSubtitle(id: Long) = _state.update {
        val next = if (id in it.checkedSubtitles) it.checkedSubtitles - id else it.checkedSubtitles + id
        it.copy(checkedSubtitles = next, focusSubtitle = id)
    }

    fun toggleTag(id: Long) = _state.update {
        val next = if (id in it.checkedTags) it.checkedTags - id else it.checkedTags + id
        it.copy(checkedTags = next, focusTag = id)
    }

    fun toggleAll() {
        _state.update { state ->
            if (state.tab == CloudLibraryTab.Subtitles) {
                val ids = state.subtitles.map { it.id }.toSet()
                val checked = if (ids.isNotEmpty() && state.checkedSubtitles.containsAll(ids)) emptySet() else ids
                state.copy(checkedSubtitles = checked)
            } else {
                val ids = state.tags.map { it.id }.toSet()
                val checked = if (ids.isNotEmpty() && state.checkedTags.containsAll(ids)) emptySet() else ids
                state.copy(checkedTags = checked)
            }
        }
    }

    fun viewCurrent() {
        val state = _state.value
        if (state.tab == CloudLibraryTab.Subtitles) {
            val id = state.focusSubtitle ?: return show("请先点选一行，再查看。")
            viewModelScope.launch {
                try {
                    val item = repository.subtitle(id)
                    _state.update { it.copy(viewerTitle = item.subtitleName, viewerBody = item.content.orEmpty()) }
                } catch (e: CloudException) {
                    show(e.message ?: "无法查看")
                }
            }
        } else {
            val id = state.focusTag ?: return show("请先点选一行，再查看。")
            viewModelScope.launch {
                try {
                    val item = repository.tag(id)
                    val body = item.document?.let { describeCloudTagDocument(it) } ?: "没有标签内容"
                    _state.update { it.copy(viewerTitle = item.subtitleName, viewerBody = body) }
                } catch (e: CloudException) {
                    show(e.message ?: "无法查看")
                }
            }
        }
    }

    fun closeViewer() = _state.update { it.copy(viewerTitle = null, viewerBody = null) }

    fun requestDelete() {
        if (operatingIds().isEmpty()) {
            show("请先点选一行。")
            return
        }
        _state.update { it.copy(confirmDelete = true) }
    }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val ids = operatingIds()
        val subtitles = _state.value.tab == CloudLibraryTab.Subtitles
        _state.update { it.copy(confirmDelete = false, loading = true) }
        viewModelScope.launch {
            try {
                if (subtitles) repository.deleteSubtitles(ids) else repository.deleteTags(ids)
                show("已删除 ${ids.size} 条。其他设备也不能再同步这些内容。")
                refresh()
            } catch (e: CloudException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    fun shareButtonLabel(): String {
        val flags = operatingSubtitles().map { it.shared }
        return when {
            flags.isEmpty() -> "设为共享"
            flags.all { it } -> "取消共享"
            flags.all { !it } -> "设为共享"
            else -> "更改共享"
        }
    }

    fun onShareClick() {
        val items = operatingSubtitles()
        if (items.isEmpty()) {
            show("请先点选一行。")
            return
        }
        when {
            items.all { it.shared } -> applyShare(items.map { it.id }, false)
            items.all { !it.shared } -> applyShare(items.map { it.id }, true)
            else -> _state.update { it.copy(askMixedShare = true) }
        }
    }

    fun chooseMixedShare(shared: Boolean?) {
        _state.update { it.copy(askMixedShare = false) }
        if (shared == null) return
        applyShare(operatingSubtitles().map { it.id }, shared)
    }

    fun uploadSubtitles(uris: List<Uri>, batch: Boolean) = beginUpload(batch) {
        repository.prepareSubtitleUris(uris)
    }

    fun uploadSubtitleFolder(treeUri: Uri) {
        library.persistTreePermission(treeUri)
        beginUpload(batch = true) { repository.prepareSubtitleTree(treeUri) }
    }

    fun uploadTags(uris: List<Uri>) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val report = repository.uploadTagUris(uris)
                _state.update { it.copy(loading = false, message = report) }
                refresh()
            } catch (e: CloudException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    fun chooseShare(shared: Boolean?) {
        _state.update { it.copy(askShare = false) }
        if (shared == null) {
            show(joinNotes("已取消上传", prepared.notes))
            return
        }
        sharedChoice = shared
        if (prepared.ready.any { it.conflict }) {
            _state.update { it.copy(askReplace = true, replaceIsBatch = uploadIsBatch) }
        } else {
            commit(skipConflicts = false)
        }
    }

    fun chooseReplace(skipExisting: Boolean?) {
        _state.update { it.copy(askReplace = false) }
        if (skipExisting == null) {
            show("已取消上传")
            return
        }
        commit(skipConflicts = skipExisting)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun timeLabel(updatedAt: String): String = formatUtcLocal(updatedAt)

    private fun beginUpload(batch: Boolean, load: suspend () -> UploadPreparation) {
        uploadIsBatch = batch
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                prepared = load()
                if (prepared.ready.isEmpty()) {
                    _state.update { it.copy(loading = false, message = joinNotes("没有需要上传的字幕", prepared.notes)) }
                } else {
                    _state.update { it.copy(loading = false, askShare = true) }
                }
            } catch (e: CloudException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    private fun commit(skipConflicts: Boolean) {
        val items = prepared.ready
        val notes = prepared.notes
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val report = repository.commitSubtitles(items, sharedChoice, skipConflicts)
                _state.update { it.copy(loading = false, message = joinNotes(report, notes)) }
                refresh()
            } catch (e: CloudException) {
                _state.update { it.copy(loading = false, message = e.message) }
            }
        }
    }

    private fun applyShare(ids: List<Long>, shared: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.setShared(ids, shared)
                show(if (shared) "已设为共享" else "已取消共享")
                refresh()
            } catch (e: CloudException) {
                show(e.message ?: "无法修改共享")
            }
        }
    }

    private fun operatingIds(): List<Long> {
        val state = _state.value
        return if (state.tab == CloudLibraryTab.Subtitles) {
            operatingSubtitles().map { it.id }
        } else {
            val checked = state.tags.filter { it.id in state.checkedTags }
            if (checked.isNotEmpty()) return checked.map { it.id }
            state.tags.filter { it.id == state.focusTag }.map { it.id }
        }
    }

    private fun operatingSubtitles(): List<LibrarySubtitle> {
        val state = _state.value
        val checked = state.subtitles.filter { it.id in state.checkedSubtitles }
        if (checked.isNotEmpty()) return checked
        return state.subtitles.filter { it.id == state.focusSubtitle }
    }

    private fun show(text: String) = _state.update { it.copy(message = text) }

    private fun joinNotes(head: String, notes: List<String>): String =
        (listOf(head) + notes).filter { it.isNotBlank() }.joinToString("\n")
}
