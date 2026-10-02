package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot

/**
 * Activity-scoped screenshot-manage dialog + viewer/edit cycle.
 * Usable from the library home and the player.
 *
 * @param currentVideoOnly when true (player「截图预览」), only lists the open video's shots.
 */
@Composable
fun ScreenshotManageFlow(
    visible: Boolean,
    onDismiss: () -> Unit,
    currentMediaUri: String? = null,
    currentCues: List<SubtitleCue> = emptyList(),
    customTagNames: List<String> = emptyList(),
    onCurrentMediaShotChanged: () -> Unit = {},
    currentVideoOnly: Boolean = false,
    currentVideoLabel: String = "",
    loadCurrentVideoItems: (suspend () -> List<com.jianqiaofan.subtitleplayer.data.ManagedScreenshot>)? = null,
) {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.applicationContext as Application
    val manageVm: ScreenshotManageViewModel = viewModel(
        viewModelStoreOwner = activity,
        key = if (currentVideoOnly) "screenshot-manage-current-video" else "screenshot-manage-folder",
        factory = ScreenshotManageViewModel.factory(app),
    )
    val manage by manageVm.manageState.collectAsStateWithLifecycle()
    var viewerSession by remember { mutableStateOf<ManageViewerSession?>(null) }
    var viewerEditItem by remember { mutableStateOf<ViewerItem?>(null) }

    LaunchedEffect(visible, currentVideoOnly) {
        if (!visible || !currentVideoOnly) return@LaunchedEffect
        val loader = loadCurrentVideoItems ?: return@LaunchedEffect
        manageVm.setCurrentVideoItems(loader(), currentVideoLabel)
    }

    LaunchedEffect(manage.message) {
        if (!manage.message.isNullOrBlank()) {
            manageVm.consumeMessage()
        }
    }

    fun closeViewer() {
        viewerSession = null
    }

    fun onViewerSessionEnd(dirty: List<ViewerItem>) {
        manageVm.patchFromViewer(dirty)
        manageVm.commitManagedEdits(dirty) {
            if (currentMediaUri != null) onCurrentMediaShotChanged()
        }
        manageVm.flushCloudSync {
            if (currentMediaUri != null) onCurrentMediaShotChanged()
        }
        if (currentVideoOnly && loadCurrentVideoItems != null) {
            manageVm.refreshCurrentVideo(loadCurrentVideoItems)
        }
    }

    if (visible && viewerSession == null) {
        ScreenshotManageDialog(
            viewModel = manageVm,
            currentVideoOnly = currentVideoOnly,
            onDismiss = onDismiss,
            onRefreshCurrentVideo = if (currentVideoOnly && loadCurrentVideoItems != null) {
                { manageVm.refreshCurrentVideo(loadCurrentVideoItems) }
            } else {
                null
            },
            onView = { managedItems, index ->
                viewerSession = ManageViewerSession(
                    items = managedItems.map { ViewerItem(shot = it.shot, managed = it) },
                    index = index,
                )
            },
        )
    }

    val viewerEdit = viewerEditItem
    if (viewerEdit != null) {
        val cues = cuesForManagedItem(viewerEdit, currentMediaUri, currentCues, manageVm)
        ScreenshotEditDialog(
            shot = viewerEdit.shot,
            cues = cues,
            customTagNames = customTagNames,
            capturing = false,
            onSave = { updated ->
                // Cache into the viewer session; disk/cloud flush happens when look mode closes.
                viewerSession = viewerSession?.let { session ->
                    session.copy(
                        items = session.items.map { item ->
                            if (item.shot.id == updated.id) {
                                item.copy(shot = updated, managed = item.managed?.copy(shot = updated))
                            } else {
                                item
                            }
                        },
                    )
                }
                viewerEditItem = null
            },
            onCancel = { viewerEditItem = null },
        )
    }

    val viewer = viewerSession
    if (viewer != null && viewer.items.isNotEmpty()) {
        ScreenshotViewerOverlay(
            items = viewer.items,
            initialIndex = viewer.index,
            loadImage = { item -> manageVm.imageBytes(item) },
            onEditShot = { item -> viewerEditItem = item },
            onDismissRequest = { closeViewer() },
            onSessionEnd = { dirty -> onViewerSessionEnd(dirty) },
        )
    }
}

private data class ManageViewerSession(
    val items: List<ViewerItem>,
    val index: Int,
)

private fun isCurrentMedia(
    item: ViewerItem,
    currentMediaUri: String?,
    manageVm: ScreenshotManageViewModel,
): Boolean {
    val managed = item.managed ?: return false
    val current = currentMediaUri?.let { Uri.parse(it) } ?: return false
    return manageVm.sameDocument(managed.mediaUri, current)
}

private fun cuesForManagedItem(
    item: ViewerItem,
    currentMediaUri: String?,
    currentCues: List<SubtitleCue>,
    manageVm: ScreenshotManageViewModel,
): List<SubtitleCue> =
    if (isCurrentMedia(item, currentMediaUri, manageVm)) currentCues else emptyList()
