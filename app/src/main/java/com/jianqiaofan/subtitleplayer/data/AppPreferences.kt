package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun currentTreeUriOnce(): String? = currentTreeUri.first()

    suspend fun playbackSpeedOnce(): Float = playbackSpeed.first()

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
