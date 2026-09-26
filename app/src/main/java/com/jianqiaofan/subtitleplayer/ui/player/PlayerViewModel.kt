package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.SubtitleDocuments
import com.jianqiaofan.subtitleplayer.data.TagDocuments
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleTrack
import com.jianqiaofan.subtitleplayer.domain.model.isAudioFile
import com.jianqiaofan.subtitleplayer.domain.model.subtitleFormatOf
import com.jianqiaofan.subtitleplayer.domain.subtitle.autoSelectTrack
import com.jianqiaofan.subtitleplayer.domain.subtitle.effectiveRepeatEnd
import com.jianqiaofan.subtitleplayer.domain.subtitle.findCueIndexAtTime
import com.jianqiaofan.subtitleplayer.domain.subtitle.findSubtitlesForMedia
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatCueListLine
import com.jianqiaofan.subtitleplayer.domain.tags.TagAlignment
import com.jianqiaofan.subtitleplayer.domain.tags.TagEdit
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.TagListFilter
import com.jianqiaofan.subtitleplayer.domain.tags.adjacentTaggedCue
import com.jianqiaofan.subtitleplayer.domain.tags.adjacentUnmatched
import com.jianqiaofan.subtitleplayer.domain.tags.alignTags
import com.jianqiaofan.subtitleplayer.domain.tags.applyCueEdit
import com.jianqiaofan.subtitleplayer.domain.tags.applyTagEdit
import com.jianqiaofan.subtitleplayer.domain.tags.attachUnmatchedToCue
import com.jianqiaofan.subtitleplayer.domain.tags.clearTags
import com.jianqiaofan.subtitleplayer.domain.tags.collectCustomTagNames
import com.jianqiaofan.subtitleplayer.domain.tags.deleteUnmatched
import com.jianqiaofan.subtitleplayer.domain.tags.describeSyncResults
import com.jianqiaofan.subtitleplayer.domain.tags.releaseFilterIfNoTags
import com.jianqiaofan.subtitleplayer.domain.tags.updateNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RepeatRange(val startMs: Long, val endMs: Long)

data class PlayerUiState(
    val mediaName: String,
    val isAudio: Boolean,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val muted: Boolean = false,
    val tracks: List<SubtitleTrack> = emptyList(),
    val selectedTrack: SubtitleTrack? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val currentCueIndex: Int = -1,
    val repeating: Boolean = false,
    val countdownLabel: String = "倒计时",
    val countdownActive: Boolean = false,
    val writable: Boolean = true,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val displaySettings: PlayerDisplaySettings = PlayerDisplaySettings(),
    val showSubtitleList: Boolean = true,
    val attachedTags: Map<Int, TagEntry> = emptyMap(),
    val unmatchedTags: List<TagEntry> = emptyList(),
    val tagFilter: TagListFilter = TagListFilter(),
    val customTagNames: List<String> = emptyList(),
    val recentMedia: List<RecentMedia> = emptyList(),
    val folderTreeUri: String? = null,
    val message: String? = null,
    val playError: String? = null,
)

class PlayerViewModel(
    application: Application,
    private val mediaUriString: String,
    private val mediaName: String,
) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val library = MediaLibrary(application)
    private val subtitles = SubtitleDocuments(application)
    private val tagDocuments = TagDocuments(application)
    private var countdownJob: Job? = null
    private var repeatJob: Job? = null
    private var repeatRange: RepeatRange? = null
    private var countdownRemainingSec: Int = 0
    private var countdownForeground: Boolean = true
    private var snapshotCues: List<SubtitleCue> = emptyList()
    private var contentTreeUri: Uri? = null
    private var contentDirectoryUri: Uri? = null

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setRenderersFactory(DefaultRenderersFactory(application).setEnableDecoderFallback(true))
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ false,
        )
        playWhenReady = false
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val d = this@apply.duration
                    _state.update { it.copy(durationMs = d.coerceAtLeast(0L), playError = null) }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.update {
                    it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message ?: error.message ?: "不支持的封装或编码"
                _state.update {
                    it.copy(playing = false, playError = "无法播放该文件：$cause")
                }
            }
        })
    }

    private val _state = MutableStateFlow(
        PlayerUiState(
            mediaName = mediaName,
            isAudio = isAudioFile(mediaName),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { preparePlayback() }
        viewModelScope.launch {
            prefs.displaySettings.collect { settings ->
                _state.update { it.copy(displaySettings = settings) }
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
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
                val cues = _state.value.cues
                val idx = findCueIndexAtTime(cues, pos / 1000.0)
                _state.update {
                    it.copy(positionMs = pos, durationMs = dur, currentCueIndex = idx)
                }
                delay(80)
            }
        }
    }

    fun updateDisplaySettings(transform: (PlayerDisplaySettings) -> PlayerDisplaySettings) {
        viewModelScope.launch {
            prefs.updateDisplaySettings(transform)
        }
    }

    fun setImmersiveListEnabled(enabled: Boolean) {
        updateDisplaySettings { it.copy(immersiveList = enabled) }
    }

    fun setShowSubtitleList(show: Boolean) {
        _state.update { it.copy(showSubtitleList = show) }
    }

    fun setTagFilter(filter: TagListFilter) {
        _state.update { it.copy(tagFilter = releaseFilterIfNoTags(currentAlignment(), filter)) }
    }

    fun jumpTagged(forward: Boolean) {
        val snapshot = _state.value
        if (snapshot.tagFilter.showUnmatched) {
            val entry = adjacentUnmatched(snapshot.unmatchedTags, snapshot.positionMs / 1000.0, forward) ?: return
            cancelRepeat()
            player.seekTo((entry.start * 1000.0).toLong().coerceAtLeast(0L))
            startPlayback()
            return
        }
        val index = adjacentTaggedCue(
            snapshot.cues,
            currentAlignment(),
            snapshot.tagFilter,
            snapshot.currentCueIndex,
            forward,
        ) ?: return
        seekToCue(index)
    }

    fun applyTags(edit: TagEdit) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = applyTagEdit(previous, _state.value.cues, edit)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next)) publishAlignment(previous)
            else refreshCustomTags()
        }
    }

    fun clearCueTags(cueIndices: Collection<Int>) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = clearTags(previous, cueIndices)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next)) publishAlignment(previous)
        }
    }

    fun saveCueNote(cueIndex: Int, note: String) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = updateNote(previous, cueIndex, note)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next)) publishAlignment(previous)
        }
    }

    fun attachUnmatched(entryId: String) {
        if (!ensureTagWritable()) return
        val cueIndex = _state.value.currentCueIndex
        val cue = _state.value.cues.getOrNull(cueIndex)
        if (cue == null) {
            _state.update { it.copy(message = "请先播放到要挂上的那一句") }
            return
        }
        val previous = currentAlignment()
        val next = attachUnmatchedToCue(previous, entryId, cueIndex, cue)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next)) publishAlignment(previous)
        }
    }

    fun deleteUnmatched(entryId: String) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = deleteUnmatched(previous, entryId)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next)) publishAlignment(previous)
        }
    }

    fun syncTagFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val tree = contentTreeUri ?: prefs.currentTreeUriOnce()?.let { Uri.parse(it) }
            val directory = contentDirectoryUri ?: tree?.let { library.treeDocumentUri(it) }
            if (tree == null || directory == null) {
                _state.update { it.copy(message = "请先通过学习文件夹打开视频，再同步标签") }
                return@launch
            }
            uris.forEach { library.persistReadPermission(it) }
            val results = tagDocuments.syncIntoFolder(tree, directory, uris)
            val track = _state.value.selectedTrack
            if (track != null && results.any { it.subtitleFileName == track.fileName && it.document != null }) {
                loadTags(track, _state.value.cues, resetFilter = false)
            }
            _state.update { it.copy(message = describeSyncResults(results)) }
        }
    }

    fun reloadTagsFromDisk() {
        val track = _state.value.selectedTrack ?: return
        viewModelScope.launch { loadTags(track, _state.value.cues, resetFilter = false) }
    }

    fun openPickedMedia(uri: Uri, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            library.persistReadPermission(uri)
            val name = withContext(Dispatchers.IO) { library.displayNameOf(uri) } ?: "媒体"
            val folder = _state.value.folderTreeUri?.let { library.folderDisplayName(Uri.parse(it)) }.orEmpty()
            prefs.rememberMedia(uri.toString(), name, folder)
            onOpen(uri.toString(), name)
        }
    }

    fun cueLine(index: Int): String {
        val cue = _state.value.cues.getOrNull(index) ?: return ""
        return formatCueListLine(cue)
    }

    fun pauseForNavigation() {
        cancelRepeat()
        if (player.isPlaying) player.pause()
    }

    fun togglePlayPause() {
        cancelRepeat()
        if (player.isPlaying) {
            player.pause()
        } else {
            startPlayback()
        }
    }

    fun seekTo(positionMs: Long, fromUser: Boolean) {
        if (fromUser) cancelRepeat()
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekToCue(index: Int) {
        val cue = _state.value.cues.getOrNull(index) ?: return
        cancelRepeat()
        player.seekTo((cue.start * 1000).toLong().coerceAtLeast(0L))
        startPlayback()
    }

    fun startRepeat(index: Int) {
        val cue = _state.value.cues.getOrNull(index) ?: return
        val durationSec = (_state.value.durationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0L)) / 1000.0
        val endSec = effectiveRepeatEnd(cue.start, cue.end, durationSec)
        val startMs = (cue.start * 1000).toLong().coerceAtLeast(0L)
        val endMs = (endSec * 1000).toLong().coerceAtLeast(startMs + 1)
        repeatRange = RepeatRange(startMs, endMs)
        _state.update { it.copy(repeating = true) }
        player.seekTo(startMs)
        startPlayback()
        repeatJob?.cancel()
        repeatJob = viewModelScope.launch {
            while (isActive && repeatRange != null) {
                val range = repeatRange ?: break
                if (player.currentPosition >= range.endMs) {
                    player.pause()
                    delay(500)
                    if (repeatRange != range) break
                    player.seekTo(range.startMs)
                    startPlayback()
                }
                delay(40)
            }
        }
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
        viewModelScope.launch { prefs.setPlaybackSpeed(speed) }
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        player.volume = if (muted) 0f else 1f
        _state.update { it.copy(muted = muted) }
    }

    fun selectTrack(track: SubtitleTrack) {
        viewModelScope.launch { loadTrack(track) }
    }

    fun startCountdown(minutes: Int) {
        val clamped = minutes.coerceIn(1, 600)
        countdownJob?.cancel()
        countdownRemainingSec = clamped * 60
        _state.update { it.copy(countdownActive = true, countdownLabel = formatCountdown(countdownRemainingSec)) }
        countdownJob = viewModelScope.launch {
            while (isActive && countdownRemainingSec > 0) {
                delay(1000)
                if (!countdownForeground) continue
                countdownRemainingSec -= 1
                if (countdownRemainingSec <= 0) {
                    _state.update { it.copy(countdownActive = false, countdownLabel = "倒计时") }
                } else {
                    _state.update { it.copy(countdownLabel = formatCountdown(countdownRemainingSec)) }
                }
            }
        }
    }

    fun setForeground(resumed: Boolean) {
        countdownForeground = resumed
        if (!resumed) saveLeavePoint()
    }

    private fun saveLeavePoint() {
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L }
            ?: _state.value.durationMs.takeIf { it > 0L }
            ?: 0L
        viewModelScope.launch(NonCancellable) {
            prefs.savePlayback(mediaUriString, position, duration, System.currentTimeMillis())
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, playError = null) }
    }

    fun showTransientMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun saveCue(index: Int, start: Double, end: Double, text: String) {
        val current = _state.value.cues.toMutableList()
        val existing = current.getOrNull(index) ?: return
        val updated = existing.copy(start = start, end = end, text = text)
        snapshotCues = current.toList()
        val previousAlignment = currentAlignment()
        current[index] = updated
        val nextAlignment = applyCueEdit(previousAlignment, index, updated)
        val track = _state.value.selectedTrack ?: run {
            _state.update { it.copy(message = "没有可写回的字幕文件") }
            return
        }
        if (!_state.value.writable) {
            _state.update { it.copy(message = "当前目录没有写权限，无法保存") }
            return
        }
        val format = subtitleFormatOf(track.fileName) ?: run {
            _state.update { it.copy(message = "不支持的字幕格式") }
            return
        }
        _state.update { it.copy(cues = current) }
        publishAlignment(nextAlignment)
        viewModelScope.launch {
            val result = subtitles.writeCues(Uri.parse(track.documentUri), current, format)
            if (result.isFailure) {
                _state.update {
                    it.copy(
                        cues = snapshotCues,
                        message = "保存失败：${result.exceptionOrNull()?.message ?: "无法写入文件"}",
                    )
                }
                publishAlignment(previousAlignment)
            } else if (!persistAlignment(nextAlignment)) {
                subtitles.writeCues(Uri.parse(track.documentUri), snapshotCues, format)
                _state.update { it.copy(cues = snapshotCues) }
                publishAlignment(previousAlignment)
            } else {
                _state.update { it.copy(message = "已保存") }
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun startPlayback() {
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.play()
    }

    private suspend fun preparePlayback() {
        val speed = prefs.playbackSpeedOnce()
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
        val mediaUri = Uri.parse(mediaUriString)
        val opened = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver
                    .openAssetFileDescriptor(mediaUri, "r")
                    ?.use { true }
                    ?: false
            } catch (e: Exception) {
                _state.update {
                    it.copy(playError = "无法打开媒体文件：${e.message ?: "没有访问权限"}")
                }
                false
            }
        }
        if (!opened) {
            if (_state.value.playError == null) {
                _state.update { it.copy(playError = "无法打开媒体文件") }
            }
            return
        }
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.prepare()
        val saved = prefs.loadPosition(mediaUriString)
        if (saved != null && saved > 1_000L) {
            player.seekTo(saved)
        }
        val tree = prefs.currentTreeUriOnce()?.let { Uri.parse(it) }
        val located = withContext(Dispatchers.IO) { library.locateMediaDirectory(mediaUri) }
        val folderTree = located?.treeUri ?: tree
        val directory = located?.directoryUri ?: folderTree?.let { library.treeDocumentUri(it) }
        if (folderTree == null || directory == null) {
            _state.update {
                it.copy(
                    folderTreeUri = null,
                    message = "未找到字幕。请先选择该视频所在的文件夹，再打开视频。",
                )
            }
            return
        }
        contentTreeUri = folderTree
        contentDirectoryUri = directory
        val folderLabel = located?.directoryName
            ?: library.folderDisplayName(folderTree)
        prefs.rememberMedia(mediaUriString, mediaName, folderLabel)
        val writable = library.treeIsWritable(folderTree)
        val resolvedName = withContext(Dispatchers.IO) {
            library.displayNameOf(mediaUri) ?: mediaName
        }
        val tracks = findSubtitlesForMedia(
            resolvedName,
            library.listSubtitleFilesIn(folderTree, directory),
        )
        _state.update {
            it.copy(folderTreeUri = folderTree.toString(), tracks = tracks, writable = writable)
        }
        val preferred = autoSelectTrack(tracks)
        if (preferred?.displayName == "同步") {
            loadTrack(preferred)
            return
        }
        var loaded = false
        for (track in tracks) {
            val format = subtitleFormatOf(track.fileName) ?: continue
            val cues = subtitles.readCues(Uri.parse(track.documentUri), format)
            if (cues.isNotEmpty()) {
                _state.update { it.copy(selectedTrack = track, cues = cues) }
                loadTags(track, cues, resetFilter = true)
                loaded = true
                break
            }
        }
        if (!loaded) {
            _state.update {
                it.copy(
                    selectedTrack = preferred,
                    cues = emptyList(),
                    message = if (tracks.isEmpty()) "未找到字幕" else null,
                )
            }
        }
    }

    private suspend fun loadTrack(track: SubtitleTrack) {
        val format = subtitleFormatOf(track.fileName)
        if (format == null) {
            _state.update { it.copy(selectedTrack = track, cues = emptyList(), message = "不支持的字幕格式") }
            return
        }
        val cues = try {
            subtitles.readCues(Uri.parse(track.documentUri), format)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    selectedTrack = track,
                    cues = emptyList(),
                    message = "字幕解析失败：${e.message ?: ""}",
                )
            }
            return
        }
        _state.update { it.copy(selectedTrack = track, cues = cues, message = null) }
        loadTags(track, cues, resetFilter = true)
    }

    private suspend fun loadTags(track: SubtitleTrack, cues: List<SubtitleCue>, resetFilter: Boolean) {
        val tree = contentTreeUri
        val directory = contentDirectoryUri
        val alignment = if (tree == null || directory == null) {
            TagAlignment()
        } else {
            val document = tagDocuments.readInFolder(tree, directory, track.fileName)
            if (document == null || document.subtitleFile != track.fileName) {
                TagAlignment()
            } else {
                alignTags(cues, document.entries)
            }
        }
        val filter = if (resetFilter) TagListFilter() else _state.value.tagFilter
        publishAlignment(alignment, filter)
        refreshCustomTags()
    }

    private suspend fun refreshCustomTags() {
        val tree = contentTreeUri ?: return
        val directory = contentDirectoryUri ?: return
        val documents = _state.value.tracks.mapNotNull { track ->
            tagDocuments.readInFolder(tree, directory, track.fileName)
        }
        _state.update { it.copy(customTagNames = collectCustomTagNames(documents)) }
    }

    private fun currentAlignment(): TagAlignment =
        TagAlignment(_state.value.attachedTags, _state.value.unmatchedTags)

    private fun publishAlignment(alignment: TagAlignment, filter: TagListFilter = _state.value.tagFilter) {
        _state.update {
            it.copy(
                attachedTags = alignment.attached,
                unmatchedTags = alignment.unmatched,
                tagFilter = releaseFilterIfNoTags(alignment, filter),
            )
        }
    }

    private suspend fun persistAlignment(alignment: TagAlignment): Boolean {
        val track = _state.value.selectedTrack ?: return false
        val tree = contentTreeUri ?: return false
        val directory = contentDirectoryUri ?: return false
        val document = com.jianqiaofan.subtitleplayer.domain.tags.alignmentToDocument(track.fileName, alignment)
        val result = tagDocuments.saveInFolder(tree, directory, track.fileName, document)
        if (result.isFailure) {
            _state.update {
                it.copy(message = "标签保存失败：${result.exceptionOrNull()?.message ?: "无法写入文件"}")
            }
        }
        return result.isSuccess
    }

    private fun ensureTagWritable(): Boolean {
        if (_state.value.selectedTrack == null) {
            _state.update { it.copy(message = "没有字幕文件，无法打标签") }
            return false
        }
        if (!_state.value.writable) {
            _state.update { it.copy(message = "当前目录没有写权限，无法保存标签") }
            return false
        }
        return true
    }

    private fun cancelRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        repeatRange = null
        _state.update { it.copy(repeating = false) }
    }

    private fun formatCountdown(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        fun factory(
            application: Application,
            mediaUri: String,
            mediaName: String,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerViewModel(application, mediaUri, mediaName) as T
                }
            }
    }
}
