package com.jianqiaofan.subtitleplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.domain.model.MediaEntry
import com.jianqiaofan.subtitleplayer.domain.model.RecentFolder
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.playback.PlaybackRecord
import com.jianqiaofan.subtitleplayer.domain.playback.resolvePlayedPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val recents: List<RecentFolder> = emptyList(),
    val recentMedia: List<RecentMedia> = emptyList(),
    val currentTreeUri: String? = null,
    val currentFolderName: String? = null,
    val media: List<MediaEntry> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val library = MediaLibrary(application)
    private var folderMedia: List<MediaEntry> = emptyList()
    private var playback: Map<String, PlaybackRecord> = emptyMap()

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.recentFolders.collectLatest { recents ->
                _state.update { it.copy(recents = recents) }
            }
        }
        viewModelScope.launch {
            prefs.recentMedia.collectLatest { stored ->
                val visible = withContext(Dispatchers.IO) {
                    stored.filter { library.documentExists(Uri.parse(it.uri)) }
                }
                _state.update { it.copy(recentMedia = visible) }
            }
        }
        viewModelScope.launch {
            prefs.playbackRecords.collectLatest { records ->
                playback = records
                publishFolderMedia()
            }
        }
        viewModelScope.launch {
            val tree = prefs.currentTreeUriOnce()
            if (tree != null) {
                openTree(Uri.parse(tree), addRecent = false)
            }
        }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = library.persistTreePermission(uri)
            if (!ok) {
                _state.update { it.copy(message = "无法保持该文件夹的访问权限，请再选一次") }
                return@launch
            }
            openTree(uri, addRecent = true)
        }
    }

    fun onRecentClicked(folder: RecentFolder) {
        viewModelScope.launch {
            openTree(Uri.parse(folder.treeUri), addRecent = false)
        }
    }

    fun rememberAndOpen(media: MediaEntry, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            prefs.rememberMedia(
                media.documentUri,
                media.displayName,
                _state.value.currentFolderName.orEmpty(),
            )
            onOpen(media.documentUri, media.displayName)
        }
    }

    fun onMediaFilePicked(uri: Uri, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            library.persistReadPermission(uri)
            val name = withContext(Dispatchers.IO) { library.displayNameOf(uri) } ?: "媒体"
            prefs.rememberMedia(uri.toString(), name, _state.value.currentFolderName.orEmpty())
            onOpen(uri.toString(), name)
        }
    }

    fun removeRecentFolder(treeUri: String) {
        viewModelScope.launch { prefs.removeRecentFolder(treeUri) }
    }

    fun setFolderRemark(treeUri: String, remark: String) {
        viewModelScope.launch { prefs.setFolderRemark(treeUri, remark) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun openTree(uri: Uri, addRecent: Boolean) {
        val name = library.folderDisplayName(uri)
        if (!library.hasPersistedAccess(uri)) {
            _state.update {
                it.copy(
                    message = "该文件夹权限已失效，请重新选择",
                    currentTreeUri = uri.toString(),
                    currentFolderName = name,
                    media = emptyList(),
                    loading = false,
                )
            }
            folderMedia = emptyList()
            return
        }
        _state.update { it.copy(loading = true, message = null) }
        if (addRecent) prefs.rememberFolder(uri.toString(), name)
        else prefs.setCurrentTree(uri.toString())
        val media = try {
            library.listMedia(uri)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    currentTreeUri = uri.toString(),
                    currentFolderName = name,
                    media = emptyList(),
                    message = "无法读取文件夹：${e.message ?: e.javaClass.simpleName}",
                )
            }
            folderMedia = emptyList()
            return
        }
        folderMedia = media
        _state.update {
            it.copy(
                loading = false,
                currentTreeUri = uri.toString(),
                currentFolderName = name,
                media = media.withPlayback(playback),
            )
        }
    }

    private fun publishFolderMedia() {
        if (folderMedia.isEmpty() && _state.value.media.isEmpty()) return
        _state.update { it.copy(media = folderMedia.withPlayback(playback)) }
    }
}

private fun List<MediaEntry>.withPlayback(records: Map<String, PlaybackRecord>): List<MediaEntry> =
    map { entry ->
        val record = records[entry.documentUri]
        entry.copy(
            lastLeftAt = record?.leftAt?.takeIf { it > 0L },
            playedPercent = record?.let { resolvePlayedPercent(it, entry.durationMs) },
        )
    }
