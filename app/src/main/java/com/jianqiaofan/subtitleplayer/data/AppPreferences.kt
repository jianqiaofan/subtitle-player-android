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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "subtitle_player")

class AppPreferences(private val context: Context) {
    val recentFolders: Flow<List<RecentFolder>> =
        context.dataStore.data.map { prefs -> decodeFolders(prefs[KEY_RECENTS].orEmpty()) }

    val playbackSpeed: Flow<Float> =
        context.dataStore.data.map { prefs -> prefs[KEY_SPEED] ?: 1.0f }

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
            val existing = decodeFolders(prefs[KEY_RECENTS].orEmpty())
                .filterNot { it.treeUri == treeUri }
            val next = listOf(RecentFolder(treeUri, displayName, now)) + existing
            prefs[KEY_RECENTS] = encodeFolders(next.take(MAX_RECENTS))
            prefs[KEY_CURRENT_TREE] = treeUri
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

    suspend fun savePosition(mediaUri: String, positionMs: Long) {
        context.dataStore.edit { prefs ->
            val map = decodePositions(prefs[KEY_POSITIONS].orEmpty()).toMutableMap()
            map[mediaUri] = positionMs
            prefs[KEY_POSITIONS] = encodePositions(map)
        }
    }

    suspend fun loadPosition(mediaUri: String): Long? =
        decodePositions(context.dataStore.data.first()[KEY_POSITIONS].orEmpty())[mediaUri]

    companion object {
        const val MAX_RECENTS = 8
        private val KEY_RECENTS = stringPreferencesKey("recent_folders")
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
            folders.joinToString("\n") { "${it.treeUri}\t${it.displayName}\t${it.lastOpenedAt}" }

        private fun decodeFolders(raw: String): List<RecentFolder> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    RecentFolder(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
                }
                .toList()

        private fun encodePositions(map: Map<String, Long>): String =
            map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

        private fun decodePositions(raw: String): Map<String, Long> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) return@mapNotNull null
                    val value = parts[1].toLongOrNull() ?: return@mapNotNull null
                    parts[0] to value
                }
                .toMap()
    }
}
