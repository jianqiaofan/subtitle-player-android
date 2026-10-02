package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.ManagedScreenshot
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.ScreenshotRepository
import com.jianqiaofan.subtitleplayer.data.ScreenshotScanner
import com.jianqiaofan.subtitleplayer.domain.model.RecentFolder
import com.jianqiaofan.subtitleplayer.domain.screenshot.ManagePagingMode
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotContentSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cached screenshot-manage list for the app session (survives dialog open/close). */
data class ScreenshotManageUiState(
    val folderUri: String? = null,
    val folderLabel: String = "",
    val recents: List<RecentFolder> = emptyList(),
    val items: List<ManagedScreenshot> = emptyList(),
    val scanning: Boolean = false,
    val paging: ManagePagingMode = ManagePagingMode.All,
    val pageIndex: Int = 0,
    val selectedRow: Int = -1,
    /** True after a successful scan for [folderUri]; returning from preview reuses this. */
    val scanned: Boolean = false,
    val message: String? = null,
    /** Player「截图预览」: only the currently open video. */
    val currentVideoOnly: Boolean = false,
)

/**
 * Activity-scoped manage list + deferred cloud sync for managed (possibly cross-video) shots.
 */
class ScreenshotManageViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val library = MediaLibrary(application)
    private val screenshotsRepo = ScreenshotRepository(application)
    private val screenshotScanner = ScreenshotScanner(application)

    private val _manage = MutableStateFlow(ScreenshotManageUiState())
    val manageState: StateFlow<ScreenshotManageUiState> = _manage.asStateFlow()

    private val pendingCloudSync = linkedMapOf<String, PendingSync>()
    private var saveJob: Job? = null

    private data class PendingSync(
        val treeUri: Uri,
        val bundleUri: Uri,
        val mediaUri: Uri,
        val mediaName: String,
        val mediaPath: String,
    )

    fun prepare() {
        viewModelScope.launch {
            if (_manage.value.currentVideoOnly) return@launch
            val recents = prefs.screenshotManageRecentsOnce()
            _manage.update { it.copy(recents = recents) }
            val remembered = prefs.screenshotManageFolderOnce() ?: return@launch
            val cached = _manage.value
            if (cached.folderUri == remembered && cached.scanned) {
                if (cached.folderLabel.isBlank()) {
                    val label = recents.firstOrNull { it.treeUri == remembered }?.displayName.orEmpty()
                    _manage.update { it.copy(folderLabel = label.ifBlank { it.folderLabel }) }
                }
                return@launch
            }
            val uri = Uri.parse(remembered)
            if (library.hasPersistedAccess(uri)) {
                scanFolder(
                    uri,
                    recents.firstOrNull { it.treeUri == remembered }?.displayName,
                    force = false,
                )
            }
        }
    }

    /** Replace the list with shots of the currently open video (no folder scan). */
    fun setCurrentVideoItems(items: List<ManagedScreenshot>, label: String) {
        _manage.value = ScreenshotManageUiState(
            folderLabel = label,
            items = items,
            scanning = false,
            scanned = true,
            currentVideoOnly = true,
            paging = ManagePagingMode.All,
        )
    }

    fun refreshCurrentVideo(loader: suspend () -> List<ManagedScreenshot>) {
        viewModelScope.launch {
            _manage.update { it.copy(scanning = true) }
            val items = loader()
            _manage.update {
                it.copy(
                    items = items,
                    scanning = false,
                    scanned = true,
                    pageIndex = 0,
                    selectedRow = -1,
                    currentVideoOnly = true,
                )
            }
        }
    }

    fun selectFolder(uri: Uri, displayName: String? = null) {
        viewModelScope.launch { scanFolder(uri, displayName, force = true) }
    }

    fun refreshFolder() {
        if (_manage.value.currentVideoOnly) return
        val uri = _manage.value.folderUri?.let { Uri.parse(it) } ?: return
        viewModelScope.launch {
            scanFolder(uri, _manage.value.folderLabel.ifBlank { null }, force = true)
        }
    }

    fun setPaging(mode: ManagePagingMode) {
        _manage.update { it.copy(paging = mode, pageIndex = 0) }
    }

    fun setPageIndex(index: Int) {
        _manage.update { it.copy(pageIndex = index.coerceAtLeast(0)) }
    }

    fun setSelectedRow(index: Int) {
        _manage.update { it.copy(selectedRow = index) }
    }

    fun consumeMessage() {
        _manage.update { it.copy(message = null) }
    }

    fun patchFromViewer(items: List<ViewerItem>) {
        if (items.isEmpty()) return
        val byId = items.associate { it.shot.id to it.shot }
        _manage.update { state ->
            state.copy(
                items = state.items.map { managed ->
                    val updated = byId[managed.shot.id] ?: return@map managed
                    managed.copy(
                        shot = updated,
                        createdAt = updated.createdAt.takeIf { it > 0L } ?: managed.createdAt,
                        updatedAt = updated.updatedAt.takeIf { it > 0L } ?: managed.updatedAt,
                    )
                },
            )
        }
    }

    fun saveManagedShot(item: ViewerItem, shot: ScreenshotShot, onSavedCurrentMedia: (() -> Unit)? = null) {
        if (screenshotContentSame(item.shot, shot)) return
        val managed = item.managed ?: return
        saveJob = viewModelScope.launch {
            val error = screenshotsRepo.save(managed.treeUri, managed.bundleUri, shot)
            if (!error.isNullOrBlank()) {
                _manage.update { it.copy(message = error) }
                return@launch
            }
            patchShot(shot)
            pendingCloudSync["${managed.treeUri}|${managed.bundleUri}"] = PendingSync(
                treeUri = managed.treeUri,
                bundleUri = managed.bundleUri,
                mediaUri = managed.mediaUri,
                mediaName = managed.mediaName,
                mediaPath = managed.mediaUri.toString(),
            )
            onSavedCurrentMedia?.invoke()
        }
    }

    /** Persist manage-viewer session edits once when leaving look mode. */
    fun commitManagedEdits(dirty: List<ViewerItem>, onSavedCurrentMedia: (() -> Unit)? = null) {
        if (dirty.isEmpty()) return
        saveJob = viewModelScope.launch {
            for (item in dirty) {
                val managed = item.managed ?: continue
                val error = screenshotsRepo.save(managed.treeUri, managed.bundleUri, item.shot)
                if (!error.isNullOrBlank()) {
                    _manage.update { it.copy(message = error) }
                    continue
                }
                patchShot(item.shot)
                pendingCloudSync["${managed.treeUri}|${managed.bundleUri}"] = PendingSync(
                    treeUri = managed.treeUri,
                    bundleUri = managed.bundleUri,
                    mediaUri = managed.mediaUri,
                    mediaName = managed.mediaName,
                    mediaPath = managed.mediaUri.toString(),
                )
            }
            onSavedCurrentMedia?.invoke()
        }
    }

    fun flushCloudSync(onSyncedCurrentMedia: (() -> Unit)? = null) {
        viewModelScope.launch {
            saveJob?.join()
            val targets = pendingCloudSync.values.toList()
            pendingCloudSync.clear()
            if (targets.isEmpty()) return@launch
            for (target in targets) {
                screenshotsRepo.sync(
                    target.treeUri,
                    target.bundleUri,
                    target.mediaUri,
                    target.mediaName,
                    target.mediaPath,
                    null,
                )
                onSyncedCurrentMedia?.invoke()
            }
        }
    }

    suspend fun imageBytes(item: ViewerItem): ByteArray? {
        val managed = item.managed ?: return null
        return screenshotsRepo.imageBytes(managed.treeUri, managed.bundleUri, item.shot.id)
    }

    fun sameDocument(a: Uri, b: Uri): Boolean = library.sameDocument(a, b)

    private fun patchShot(shot: ScreenshotShot) {
        _manage.update { state ->
            state.copy(
                items = state.items.map { managed ->
                    if (managed.shot.id != shot.id) managed
                    else managed.copy(
                        shot = shot,
                        createdAt = shot.createdAt.takeIf { it > 0L } ?: managed.createdAt,
                        updatedAt = shot.updatedAt.takeIf { it > 0L } ?: managed.updatedAt,
                    )
                },
            )
        }
    }

    private suspend fun scanFolder(uri: Uri, displayName: String?, force: Boolean) {
        library.persistTreePermission(uri)
        val label = displayName?.ifBlank { null } ?: library.folderDisplayName(uri)
        prefs.rememberScreenshotManageFolder(uri.toString(), label)
        val recents = prefs.screenshotManageRecentsOnce()
        val cached = _manage.value
        if (!force && cached.folderUri == uri.toString() && cached.scanned) {
            _manage.update {
                it.copy(folderUri = uri.toString(), folderLabel = label, recents = recents)
            }
            return
        }
        _manage.update {
            it.copy(
                folderUri = uri.toString(),
                folderLabel = label,
                recents = recents,
                scanning = true,
                pageIndex = 0,
                selectedRow = -1,
                currentVideoOnly = false,
            )
        }
        val scanned = withContext(Dispatchers.IO) { screenshotScanner.scan(uri) }
        _manage.update {
            it.copy(
                items = scanned,
                scanning = false,
                scanned = true,
                pageIndex = 0,
                selectedRow = -1,
                currentVideoOnly = false,
            )
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScreenshotManageViewModel(application) as T
                }
            }
    }
}
