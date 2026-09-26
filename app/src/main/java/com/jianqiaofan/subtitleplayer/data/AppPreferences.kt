package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSideLandscape
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSidePortrait
import com.jianqiaofan.subtitleplayer.domain.display.OnScreenSubtitlePosition
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.display.PreferredOrientation
import com.jianqiaofan.subtitleplayer.domain.display.SubtitleListDensity
import com.jianqiaofan.subtitleplayer.domain.model.RecentFolder
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.playback.PlaybackRecord
import com.jianqiaofan.subtitleplayer.domain.playback.decodePlayback
import com.jianqiaofan.subtitleplayer.domain.playback.encodePlayback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "subtitle_player")

class AppPreferences(private val context: Context) {
    val recentFolders: Flow<List<RecentFolder>> =
        context.dataStore.data.map { prefs -> decodeFolders(prefs[KEY_RECENTS].orEmpty()) }

    val playbackSpeed: Flow<Float> =
        context.dataStore.data.map { prefs -> prefs[KEY_SPEED] ?: 1.0f }

    val recentMedia: Flow<List<RecentMedia>> =
        context.dataStore.data.map { prefs -> decodeMedia(prefs[KEY_RECENT_MEDIA].orEmpty()) }

    val batchTagSyncFiles: Flow<List<RecentMedia>> =
        context.dataStore.data.map { prefs -> decodeMedia(prefs[KEY_BATCH_TAG_FILES].orEmpty()) }

    val batchTagSyncVideoDir: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[KEY_BATCH_TAG_DIR]?.ifBlank { null } }

    val playbackRecords: Flow<Map<String, PlaybackRecord>> =
        context.dataStore.data.map { prefs -> decodePlayback(prefs[KEY_POSITIONS].orEmpty()) }

    val currentTreeUri: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[KEY_CURRENT_TREE]?.ifBlank { null } }

    val displaySettings: Flow<PlayerDisplaySettings> =
        context.dataStore.data.map { prefs ->
            PlayerDisplaySettings(
                onscreenEnabled = prefs[KEY_ONSCREEN_ENABLED] ?: true,
                onscreenFontSize = prefs[KEY_ONSCREEN_FONT_SIZE] ?: 18,
                onscreenColor = prefs[KEY_ONSCREEN_COLOR] ?: "#FFFFFF",
                onscreenBgOpacity = prefs[KEY_ONSCREEN_BG_OPACITY] ?: 0.55f,
                onscreenWidthPercent = prefs[KEY_ONSCREEN_WIDTH] ?: 80,
                onscreenPosition = OnScreenSubtitlePosition.fromWire(prefs[KEY_ONSCREEN_POSITION]),
                immersiveList = prefs[KEY_IMMERSIVE] ?: false,
                immersiveListOpacity = prefs[KEY_IMMERSIVE_OPACITY] ?: 0.28f,
                immersiveListSideLandscape =
                    ImmersiveListSideLandscape.fromWire(prefs[KEY_IMMERSIVE_SIDE_LAND]),
                immersiveListSidePortrait =
                    ImmersiveListSidePortrait.fromWire(prefs[KEY_IMMERSIVE_SIDE_PORT]),
                immersiveListSizePercent = prefs[KEY_IMMERSIVE_SIZE] ?: 36,
                subtitleListDensity = SubtitleListDensity.fromWire(prefs[KEY_LIST_DENSITY]),
                preferredOrientation = PreferredOrientation.fromWire(prefs[KEY_ORIENTATION]),
            ).clamp()
        }

    suspend fun currentTreeUriOnce(): String? = currentTreeUri.first()

    suspend fun playbackSpeedOnce(): Float = playbackSpeed.first()

    suspend fun displaySettingsOnce(): PlayerDisplaySettings = displaySettings.first()

    suspend fun rememberFolder(treeUri: String, displayName: String) {
        val now = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val stored = decodeFolders(prefs[KEY_RECENTS].orEmpty())
            val remark = stored.firstOrNull { it.treeUri == treeUri }?.remark.orEmpty()
            val existing = stored.filterNot { it.treeUri == treeUri }
            val next = listOf(RecentFolder(treeUri, displayName, now, remark)) + existing
            prefs[KEY_RECENTS] = encodeFolders(next.take(MAX_RECENTS))
            prefs[KEY_CURRENT_TREE] = treeUri
        }
    }

    suspend fun setFolderRemark(treeUri: String, remark: String) {
        val cleaned = remark.trim().replace('\t', ' ').replace('\n', ' ')
        context.dataStore.edit { prefs ->
            val existing = decodeFolders(prefs[KEY_RECENTS].orEmpty()).map { folder ->
                if (folder.treeUri == treeUri) folder.copy(remark = cleaned) else folder
            }
            prefs[KEY_RECENTS] = encodeFolders(existing)
        }
    }

    suspend fun removeRecentFolder(treeUri: String) {
        context.dataStore.edit { prefs ->
            val existing = decodeFolders(prefs[KEY_RECENTS].orEmpty())
                .filterNot { it.treeUri == treeUri }
            prefs[KEY_RECENTS] = encodeFolders(existing)
        }
    }

    suspend fun setCurrentTree(treeUri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CURRENT_TREE] = treeUri
            val existing = decodeFolders(prefs[KEY_RECENTS].orEmpty()).map {
                if (it.treeUri == treeUri) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.lastOpenedAt }
            prefs[KEY_RECENTS] = encodeFolders(existing)
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_SPEED] = speed }
    }

    suspend fun saveDisplaySettings(settings: PlayerDisplaySettings) {
        val clamped = settings.clamp()
        context.dataStore.edit { prefs ->
            prefs[KEY_ONSCREEN_ENABLED] = clamped.onscreenEnabled
            prefs[KEY_ONSCREEN_FONT_SIZE] = clamped.onscreenFontSize
            prefs[KEY_ONSCREEN_COLOR] = clamped.onscreenColor
            prefs[KEY_ONSCREEN_BG_OPACITY] = clamped.onscreenBgOpacity
            prefs[KEY_ONSCREEN_WIDTH] = clamped.onscreenWidthPercent
            prefs[KEY_ONSCREEN_POSITION] = clamped.onscreenPosition.wire
            prefs[KEY_IMMERSIVE] = clamped.immersiveList
            prefs[KEY_IMMERSIVE_OPACITY] = clamped.immersiveListOpacity
            prefs[KEY_IMMERSIVE_SIDE_LAND] = clamped.immersiveListSideLandscape.wire
            prefs[KEY_IMMERSIVE_SIDE_PORT] = clamped.immersiveListSidePortrait.wire
            prefs[KEY_IMMERSIVE_SIZE] = clamped.immersiveListSizePercent
            prefs[KEY_LIST_DENSITY] = clamped.subtitleListDensity.wire
            prefs[KEY_ORIENTATION] = clamped.preferredOrientation.wire
        }
    }

    suspend fun updateDisplaySettings(transform: (PlayerDisplaySettings) -> PlayerDisplaySettings) {
        saveDisplaySettings(transform(displaySettingsOnce()))
    }

    suspend fun rememberMedia(uri: String, displayName: String, folderLabel: String) {
        if (uri.isBlank() || displayName.isBlank()) return
        context.dataStore.edit { prefs ->
            val existing = decodeMedia(prefs[KEY_RECENT_MEDIA].orEmpty())
                .filterNot { it.uri == uri }
            val next = listOf(
                RecentMedia(
                    uri = uri,
                    displayName = displayName.replace('\t', ' '),
                    folderLabel = folderLabel.replace('\t', ' '),
                ),
            ) + existing
            prefs[KEY_RECENT_MEDIA] = encodeMedia(next.take(MAX_RECENT_MEDIA))
        }
    }

    suspend fun batchTagSyncFilesOnce(): List<RecentMedia> = batchTagSyncFiles.first()

    suspend fun batchTagSyncVideoDirOnce(): String? = batchTagSyncVideoDir.first()

    suspend fun rememberBatchTagSync(files: List<RecentMedia>, videoTreeUri: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BATCH_TAG_FILES] = encodeMedia(files)
            if (videoTreeUri.isNullOrBlank()) {
                prefs.remove(KEY_BATCH_TAG_DIR)
            } else {
                prefs[KEY_BATCH_TAG_DIR] = videoTreeUri
            }
        }
    }

    suspend fun savePlayback(mediaUri: String, positionMs: Long, durationMs: Long, leftAt: Long) {
        if (mediaUri.isBlank()) return
        context.dataStore.edit { prefs ->
            val map = decodePlayback(prefs[KEY_POSITIONS].orEmpty()).toMutableMap()
            map[mediaUri] = PlaybackRecord(
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                leftAt = leftAt,
            )
            prefs[KEY_POSITIONS] = encodePlayback(map)
        }
    }

    suspend fun loadPosition(mediaUri: String): Long? =
        decodePlayback(context.dataStore.data.first()[KEY_POSITIONS].orEmpty())[mediaUri]?.positionMs

    companion object {
        const val MAX_RECENTS = 8
        const val MAX_RECENT_MEDIA = 15
        private val KEY_RECENTS = stringPreferencesKey("recent_folders")
        private val KEY_RECENT_MEDIA = stringPreferencesKey("recent_media")
        private val KEY_BATCH_TAG_FILES = stringPreferencesKey("batch_tag_sync_files")
        private val KEY_BATCH_TAG_DIR = stringPreferencesKey("batch_tag_sync_video_dir")
        private val KEY_CURRENT_TREE = stringPreferencesKey("current_tree")
        private val KEY_SPEED = floatPreferencesKey("playback_speed")
        private val KEY_POSITIONS = stringPreferencesKey("positions")

        private val KEY_ONSCREEN_ENABLED = booleanPreferencesKey("onscreen_subtitle_enabled")
        private val KEY_ONSCREEN_FONT_SIZE = intPreferencesKey("onscreen_subtitle_font_size")
        private val KEY_ONSCREEN_COLOR = stringPreferencesKey("onscreen_subtitle_color")
        private val KEY_ONSCREEN_BG_OPACITY = floatPreferencesKey("onscreen_subtitle_bg_opacity")
        private val KEY_ONSCREEN_WIDTH = intPreferencesKey("onscreen_subtitle_width_percent")
        private val KEY_ONSCREEN_POSITION = stringPreferencesKey("onscreen_subtitle_position")
        private val KEY_IMMERSIVE = booleanPreferencesKey("immersive_subtitle_list")
        private val KEY_IMMERSIVE_OPACITY = floatPreferencesKey("immersive_subtitle_list_opacity")
        private val KEY_IMMERSIVE_SIDE_LAND =
            stringPreferencesKey("immersive_subtitle_list_side_landscape")
        private val KEY_IMMERSIVE_SIDE_PORT =
            stringPreferencesKey("immersive_subtitle_list_side_portrait")
        private val KEY_IMMERSIVE_SIZE = intPreferencesKey("immersive_subtitle_list_size_percent")
        private val KEY_LIST_DENSITY = stringPreferencesKey("subtitle_list_density")
        private val KEY_ORIENTATION = stringPreferencesKey("preferred_orientation")

        private fun encodeFolders(folders: List<RecentFolder>): String =
            folders.joinToString("\n") {
                "${it.treeUri}\t${it.displayName}\t${it.lastOpenedAt}\t${it.remark}"
            }

        private fun decodeFolders(raw: String): List<RecentFolder> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val remark = if (parts.size >= 4) parts.subList(3, parts.size).joinToString("\t") else ""
                    RecentFolder(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L, remark)
                }
                .toList()

        private fun encodeMedia(items: List<RecentMedia>): String =
            items.joinToString("\n") { "${it.uri}\t${it.displayName}\t${it.folderLabel}" }

        private fun decodeMedia(raw: String): List<RecentMedia> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) return@mapNotNull null
                    RecentMedia(parts[0], parts[1], parts.getOrElse(2) { "" })
                }
                .toList()
    }
}
