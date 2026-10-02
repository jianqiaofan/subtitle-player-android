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
import com.jianqiaofan.subtitleplayer.data.CloudRepository
import com.jianqiaofan.subtitleplayer.data.DeviceNetwork
import com.jianqiaofan.subtitleplayer.data.ManagedScreenshot
import com.jianqiaofan.subtitleplayer.data.MediaBundleFiles
import com.jianqiaofan.subtitleplayer.data.MediaLibrary
import com.jianqiaofan.subtitleplayer.data.ScreenshotRepository
import com.jianqiaofan.subtitleplayer.data.renderAnnotatedJpeg
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudAnswer
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudPrompt
import com.jianqiaofan.subtitleplayer.domain.cloud.LeaveAction
import com.jianqiaofan.subtitleplayer.domain.cloud.LeavePolicy
import com.jianqiaofan.subtitleplayer.domain.cloud.PendingChanges
import com.jianqiaofan.subtitleplayer.domain.cloud.StudySyncMemory
import com.jianqiaofan.subtitleplayer.domain.cloud.itemsToUpload
import com.jianqiaofan.subtitleplayer.domain.cloud.leaveAction
import com.jianqiaofan.subtitleplayer.data.SubtitleDocuments
import com.jianqiaofan.subtitleplayer.data.TagDocuments
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.model.RecentMedia
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleTrack
import com.jianqiaofan.subtitleplayer.domain.model.isAudioFile
import com.jianqiaofan.subtitleplayer.domain.bundle.isBundleFolderName
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotNote
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.frameIndexAt
import com.jianqiaofan.subtitleplayer.domain.screenshot.managedRelativePath
import com.jianqiaofan.subtitleplayer.domain.screenshot.newScreenshotId
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotContentSame
import com.jianqiaofan.subtitleplayer.domain.screenshot.sortedScreenshots
import com.jianqiaofan.subtitleplayer.domain.screenshot.suggestedScreenshotTitle
import com.jianqiaofan.subtitleplayer.domain.model.subtitleFormatOf
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PlaybackLog
import com.jianqiaofan.subtitleplayer.domain.playbacklog.beginSession
import com.jianqiaofan.subtitleplayer.domain.playbacklog.closeSession
import com.jianqiaofan.subtitleplayer.domain.playbacklog.formatSessionLine
import com.jianqiaofan.subtitleplayer.domain.playbacklog.formatStudyDuration
import com.jianqiaofan.subtitleplayer.domain.playbacklog.mergePlaybackSessions
import com.jianqiaofan.subtitleplayer.domain.playbacklog.newPlaybackSessionId
import com.jianqiaofan.subtitleplayer.domain.playbacklog.sessionsForAccount
import com.jianqiaofan.subtitleplayer.domain.playbacklog.sessionsToUpload
import com.jianqiaofan.subtitleplayer.domain.playbacklog.studyTotalMillis
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
import com.jianqiaofan.subtitleplayer.domain.tags.companionSubtitleNames
import com.jianqiaofan.subtitleplayer.domain.tags.deleteUnmatched
import com.jianqiaofan.subtitleplayer.domain.tags.describeSyncResults
import com.jianqiaofan.subtitleplayer.domain.tags.releaseFilterIfNoTags
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleFileNameFromTagFile
import com.jianqiaofan.subtitleplayer.domain.tags.updateNote
import com.jianqiaofan.subtitleplayer.domain.timeline.TIMELINE_STEP_SECONDS
import com.jianqiaofan.subtitleplayer.domain.timeline.alignTimelineTags
import com.jianqiaofan.subtitleplayer.domain.timeline.buildTimeline
import com.jianqiaofan.subtitleplayer.domain.timeline.defaultTimelineStepSeconds
import com.jianqiaofan.subtitleplayer.domain.timeline.isTimelineSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.timeline.rehomeTimelineTags
import com.jianqiaofan.subtitleplayer.domain.timeline.timelineEntriesForSave
import com.jianqiaofan.subtitleplayer.domain.timeline.timelineSubtitleFileName
import com.jianqiaofan.subtitleplayer.domain.time.utcTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RepeatRange(val startMs: Long, val endMs: Long)

private data class LeaveChoice(
    val sync: Boolean,
    val subtitles: Boolean,
    val tags: Boolean,
    val remember: Boolean,
)

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
    val cloudPrompt: CloudPrompt? = null,
    val timelineMode: Boolean = false,
    val timelineStepSec: Int = 60,
    val studyLog: StudyLogUi? = null,
    val leavePrompt: LeaveSyncUi? = null,
    val screenshots: List<ScreenshotShot> = emptyList(),
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
    private val bundles = MediaBundleFiles(application)
    private val cloud = CloudRepository(application)
    private val screenshotsRepo = ScreenshotRepository(application)
    private var countdownJob: Job? = null
    private var repeatJob: Job? = null
    private var repeatRange: RepeatRange? = null
    private var countdownRemainingSec: Int = 0
    private var countdownForeground: Boolean = true
    private var snapshotCues: List<SubtitleCue> = emptyList()
    private var contentTreeUri: Uri? = null
    private var contentDirectoryUri: Uri? = null
    private var cloudAnswer: CompletableDeferred<CloudAnswer>? = null
    private var resolvedMediaName: String = mediaName
    private var videoHash: String? = null
    private var openSessionId: String? = null
    private var suppressSessionBoundary = false
    private val dirtySubtitles = mutableSetOf<String>()
    private val dirtyTags = mutableSetOf<String>()
    private var timelineHiddenEntries: List<TagEntry> = emptyList()
    private var timelineStepChosen = false
    private val studyMutex = Mutex()
    private var leaveGate: CompletableDeferred<Unit>? = null
    private var leaveAnswer: CompletableDeferred<LeaveChoice>? = null
    private var leaveSettled = false

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
                if (suppressSessionBoundary) return
                viewModelScope.launch {
                    if (isPlaying) startStudySession() else finishStudySession()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val d = this@apply.duration
                    _state.update { it.copy(durationMs = d.coerceAtLeast(0L), playError = null) }
                    if (_state.value.timelineMode) {
                        viewModelScope.launch { publishTimeline(d) }
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    viewModelScope.launch { performLeaveSync() }
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

    /** Bundle locations edited in the current-video viewer; cloud sync runs once on close. */
    private val pendingViewerCloudSync = linkedMapOf<String, PendingViewerScreenshotSync>()
    private var viewerSaveJob: Job? = null

    private data class PendingViewerScreenshotSync(
        val treeUri: Uri,
        val bundleUri: Uri,
        val mediaUri: Uri,
        val mediaName: String,
        val mediaPath: String,
        val knownHash: String?,
    )

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
                if (_state.value.timelineMode && _state.value.cues.isEmpty() && dur > 0L) {
                    publishTimeline(dur)
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
            if (!persistAlignment(next, fromUser = true)) publishAlignment(previous)
            else refreshCustomTags()
        }
    }

    fun clearCueTags(cueIndices: Collection<Int>) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = clearTags(previous, cueIndices)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next, fromUser = true)) publishAlignment(previous)
        }
    }

    fun saveCueNote(cueIndex: Int, note: String) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = updateNote(previous, cueIndex, note)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next, fromUser = true)) publishAlignment(previous)
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
            if (!persistAlignment(next, fromUser = true)) publishAlignment(previous)
        }
    }

    fun deleteUnmatched(entryId: String) {
        if (!ensureTagWritable()) return
        val previous = currentAlignment()
        val next = deleteUnmatched(previous, entryId)
        publishAlignment(next)
        viewModelScope.launch {
            if (!persistAlignment(next, fromUser = true)) publishAlignment(previous)
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

    fun seekToScreenshot(id: String) {
        val shot = _state.value.screenshots.find { it.id == id } ?: return
        cancelRepeat()
        player.seekTo((shot.time * 1000.0).toLong().coerceAtLeast(0L))
        startPlayback()
    }

    /** Pause and jump to the shot time before opening the create/edit dialog. */
    fun prepareEditScreenshot(id: String) {
        val shot = _state.value.screenshots.find { it.id == id } ?: return
        cancelRepeat()
        if (player.isPlaying) player.pause()
        player.seekTo((shot.time * 1000.0).toLong().coerceAtLeast(0L))
    }

    private var captureJob: Job? = null

    /**
     * Builds a draft immediately so the edit dialog can open, then grabs the frame
     * and writes files in the background. [onReady] / [onFailed] run on the main thread.
     */
    fun startCaptureScreenshot(
        onReady: () -> Unit = {},
        onFailed: (String) -> Unit = {},
    ): ScreenshotShot? {
        if (_state.value.isAudio) {
            showTransientMessage("音频没有画面，不能截图")
            return null
        }
        if (!_state.value.writable) {
            showTransientMessage("无法保存截图。请用可写的文件夹打开这部视频。")
            return null
        }
        val timeMs = player.currentPosition.coerceAtLeast(0L)
        val frameRate = player.videoFormat?.frameRate
        val timeSec = timeMs / 1000.0
        val cueIndex = findCueIndexAtTime(_state.value.cues, timeSec)
        val cueTags = if (cueIndex >= 0) {
            _state.value.attachedTags[cueIndex]?.tags.orEmpty()
        } else {
            emptyList()
        }
        val title = suggestedScreenshotTitle(mediaStem(resolvedMediaName), cueTags)
        val id = newScreenshotId()
        val now = System.currentTimeMillis()
        val draft = ScreenshotShot(
            id = id,
            title = title,
            time = timeSec,
            frame = frameIndexAt(timeSec, frameRate),
            image = "",
            createdAt = now,
            updatedAt = now,
            notes = emptyList(),
        )
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            val bundle = screenshotBundle()
            if (bundle == null) {
                onFailed("无法保存截图。请用可写的文件夹打开这部视频。")
                return@launch
            }
            val (createdId, error) = try {
                screenshotsRepo.capture(
                    bundle.first,
                    bundle.second,
                    Uri.parse(mediaUriString),
                    timeMs,
                    frameRate,
                    title = title,
                    shotId = id,
                )
            } catch (_: CancellationException) {
                // Cancel path also runs discardCapture; nothing else to do here.
                return@launch
            }
            if (!isActive) return@launch
            // null/null means this id was discarded while capture raced past cancel.
            if (createdId == null && error == null) return@launch
            if (!error.isNullOrBlank()) {
                onFailed(error)
                return@launch
            }
            refreshScreenshots()
            if (!isActive) return@launch
            onReady()
            syncScreenshots()
        }
        return draft
    }

    fun cancelCaptureScreenshot(id: String) {
        captureJob?.cancel()
        captureJob = null
        viewModelScope.launch {
            val bundle = screenshotBundle() ?: return@launch
            val error = screenshotsRepo.discardCapture(
                bundle.first,
                bundle.second,
                id,
                Uri.parse(mediaUriString),
                resolvedMediaName,
                mediaUriString,
                videoHash,
            )
            refreshScreenshots()
            if (!error.isNullOrBlank()) showTransientMessage(error)
            else {
                // Drop a not-yet-synced create; if it reached the cloud mid-flight, sync removes it.
                syncScreenshots()
            }
        }
    }

    fun saveScreenshot(shot: ScreenshotShot) {
        viewModelScope.launch {
            val bundle = screenshotBundle() ?: return@launch
            val error = screenshotsRepo.save(bundle.first, bundle.second, shot)
            refreshScreenshots()
            if (!error.isNullOrBlank()) showTransientMessage(error) else syncScreenshots()
        }
    }

    fun deleteScreenshot(id: String) {
        viewModelScope.launch {
            val bundle = screenshotBundle() ?: return@launch
            val error = screenshotsRepo.delete(
                bundle.first,
                bundle.second,
                id,
                Uri.parse(mediaUriString),
                resolvedMediaName,
                mediaUriString,
                videoHash,
            )
            refreshScreenshots()
            if (!error.isNullOrBlank()) showTransientMessage(error) else syncScreenshots()
        }
    }

    suspend fun shotImage(id: String): ByteArray? {
        val bundle = screenshotBundle() ?: return null
        return screenshotsRepo.imageBytes(bundle.first, bundle.second, id)
    }

    suspend fun plainScreenshotJpeg(id: String, notes: List<ScreenshotNote>, showNotes: Boolean): ByteArray? {
        val bytes = shotImage(id) ?: return null
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            renderAnnotatedJpeg(bitmap, notes, showNotes)
        } finally {
            bitmap.recycle()
        }
    }

    fun plainScreenshotName(title: String): String =
        com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotExportFileName(title)

    suspend fun screenshotExportUri(): String? = prefs.screenshotExportUriOnce()

    fun rememberScreenshotExport(uri: String) {
        viewModelScope.launch { prefs.rememberScreenshotExportUri(uri) }
    }

    suspend fun viewerImageBytes(item: ViewerItem): ByteArray? {
        val managed = item.managed
        return if (managed != null) {
            screenshotsRepo.imageBytes(managed.treeUri, managed.bundleUri, item.shot.id)
        } else {
            shotImage(item.shot.id)
        }
    }

    fun saveViewerShot(item: ViewerItem, shot: ScreenshotShot) {
        if (item.managed != null) return
        if (screenshotContentSame(item.shot, shot)) return
        viewerSaveJob = viewModelScope.launch {
            val bundle = screenshotBundle() ?: return@launch
            val error = screenshotsRepo.save(bundle.first, bundle.second, shot)
            if (!error.isNullOrBlank()) {
                showTransientMessage(error)
                return@launch
            }
            refreshScreenshots()
            markViewerCloudSync(
                bundle.first,
                bundle.second,
                Uri.parse(mediaUriString),
                resolvedMediaName,
                mediaUriString,
                videoHash,
            )
        }
    }

    /** Persist viewer-session edits once when leaving look mode (not on each note tweak). */
    fun commitViewerEdits(dirty: List<ViewerItem>) {
        if (dirty.isEmpty()) return
        viewerSaveJob = viewModelScope.launch {
            for (item in dirty) {
                if (item.managed != null) continue
                val bundle = screenshotBundle() ?: continue
                val error = screenshotsRepo.save(bundle.first, bundle.second, item.shot)
                if (!error.isNullOrBlank()) {
                    showTransientMessage(error)
                    continue
                }
                markViewerCloudSync(
                    bundle.first,
                    bundle.second,
                    Uri.parse(mediaUriString),
                    resolvedMediaName,
                    mediaUriString,
                    videoHash,
                )
            }
            refreshScreenshots()
        }
    }

    /** Flush deferred screenshot uploads after the current-video viewer closes. */
    fun flushViewerCloudSync() {
        viewModelScope.launch {
            viewerSaveJob?.join()
            val targets = pendingViewerCloudSync.values.toList()
            pendingViewerCloudSync.clear()
            if (targets.isEmpty()) return@launch
            for (target in targets) {
                screenshotsRepo.sync(
                    target.treeUri,
                    target.bundleUri,
                    target.mediaUri,
                    target.mediaName,
                    target.mediaPath,
                    target.knownHash,
                )
                refreshScreenshots()
            }
        }
    }

    private fun markViewerCloudSync(
        treeUri: Uri,
        bundleUri: Uri,
        mediaUri: Uri,
        mediaName: String,
        mediaPath: String,
        knownHash: String?,
    ) {
        pendingViewerCloudSync["$treeUri|$bundleUri"] = PendingViewerScreenshotSync(
            treeUri = treeUri,
            bundleUri = bundleUri,
            mediaUri = mediaUri,
            mediaName = mediaName,
            mediaPath = mediaPath,
            knownHash = knownHash,
        )
    }

    fun cuesForViewerItem(item: ViewerItem): List<com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue> {
        val managed = item.managed ?: return _state.value.cues
        return if (library.sameDocument(managed.mediaUri, Uri.parse(mediaUriString))) {
            _state.value.cues
        } else {
            emptyList()
        }
    }

    /** Reload local screenshots after manage flow edits the current video. */
    fun reloadScreenshots() {
        viewModelScope.launch { refreshScreenshots() }
    }

    /** Build manage-list rows for the open video only (used by「截图预览」). */
    suspend fun currentVideoManagedScreenshots(): List<ManagedScreenshot> {
        refreshScreenshots()
        val bundle = screenshotBundle() ?: return emptyList()
        val media = Uri.parse(mediaUriString)
        return sortedScreenshots(_state.value.screenshots).map { shot ->
            val imageName = shot.image.ifBlank { "${shot.id}.png" }
            ManagedScreenshot(
                shot = shot,
                treeUri = bundle.first,
                bundleUri = bundle.second,
                mediaUri = media,
                mediaName = resolvedMediaName,
                relativePath = managedRelativePath("", resolvedMediaName, imageName),
                relativeDir = "",
                createdAt = shot.createdAt.takeIf { it > 0L } ?: shot.updatedAt,
                updatedAt = shot.updatedAt.takeIf { it > 0L } ?: shot.createdAt,
            )
        }
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
                    suppressSessionBoundary = true
                    player.pause()
                    delay(500)
                    if (repeatRange != range) {
                        suppressSessionBoundary = false
                        break
                    }
                    player.seekTo(range.startMs)
                    startPlayback()
                    suppressSessionBoundary = false
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
        setPlaybackAudible(_state.value.muted)
    }

    fun setPlaybackAudible(audible: Boolean) {
        val muted = !audible
        if (_state.value.muted == muted && player.volume == (if (muted) 0f else 1f)) return
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

    fun acceptCloudPrompt() {
        cloudAnswer?.complete(CloudAnswer.Accept)
    }

    fun dismissCloudPrompt() {
        cloudAnswer?.complete(CloudAnswer.Dismiss)
    }

    fun acceptCloudShare(username: String) {
        cloudAnswer?.complete(CloudAnswer.Person(username))
    }

    fun showTransientMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun chooseSubtitleCopy(keepBundle: Boolean) {
        cloudAnswer?.complete(CloudAnswer.Keep(keepBundle))
    }

    fun requestLeave(then: () -> Unit) {
        viewModelScope.launch {
            suppressSessionBoundary = true
            if (player.isPlaying) player.pause()
            suppressSessionBoundary = false
            finishStudySession()
            performLeaveSync()
            then()
        }
    }

    fun setTimelineStep(seconds: Int) {
        if (!_state.value.timelineMode || seconds !in TIMELINE_STEP_SECONDS) return
        timelineStepChosen = true
        viewModelScope.launch { rebuildTimeline(seconds) }
    }

    fun showStudyLog() {
        viewModelScope.launch {
            val directory = contentDirectoryUri
            if (directory == null) {
                _state.update { it.copy(message = "请先打开视频") }
                return@launch
            }
            val username = prefs.cloudAccountOnce().username
            val owners = prefs.studyMemory().sessionOwners
            val log = withContext(Dispatchers.IO) { bundles.readPlayback(directory) }
            val visible = sessionsForAccount(log.sessions, owners, username)
                .filter { it.endedAt != null }
                .sortedByDescending { it.startedAt }
            _state.update {
                it.copy(
                    studyLog = StudyLogUi(
                        total = formatStudyDuration(studyTotalMillis(visible)),
                        lines = visible.mapNotNull { session -> formatSessionLine(session) },
                    ),
                )
            }
        }
    }

    fun dismissStudyLog() {
        _state.update { it.copy(studyLog = null) }
    }

    fun confirmLeaveSync(sync: Boolean, subtitles: Boolean, tags: Boolean, remember: Boolean) {
        leaveAnswer?.complete(LeaveChoice(sync, subtitles, tags, remember))
    }

    fun saveCue(index: Int, start: Double, end: Double, text: String) {
        if (_state.value.timelineMode) {
            _state.update { it.copy(message = "时间线只在列表里使用，不会写成字幕文件") }
            return
        }
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
                dirtySubtitles += track.fileName
                dirtyTags += track.fileName
                _state.update { it.copy(message = "已保存") }
            }
        }
    }

    override fun onCleared() {
        val directory = contentDirectoryUri
        val id = openSessionId
        if (directory != null && id != null) {
            openSessionId = null
            val log = bundles.readPlayback(directory)
            bundles.writePlayback(directory, closeSession(log, id, System.currentTimeMillis()))
        }
        if (dirtySubtitles.isNotEmpty() || dirtyTags.isNotEmpty()) {
            kotlinx.coroutines.runBlocking {
                val memory = prefs.studyMemory()
                savePending(
                    storedPending(memory).merge(
                        PendingChanges(dirtySubtitles.isNotEmpty(), dirtyTags.isNotEmpty()),
                    ),
                )
            }
        }
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
        val folderLabel = located?.directoryName
            ?: library.folderDisplayName(folderTree)
        prefs.rememberMedia(mediaUriString, mediaName, folderLabel)
        val writable = library.treeIsWritable(folderTree)
        val resolvedName = withContext(Dispatchers.IO) {
            library.displayNameOf(mediaUri) ?: mediaName
        }
        resolvedMediaName = resolvedName
        val subtitleDir = bundles.prepare(folderTree, directory, resolvedName) { prompt ->
            awaitCloudPrompt(prompt)
        }
        contentDirectoryUri = subtitleDir
        val tracks = findSubtitlesForMedia(
            resolvedName,
            library.listSubtitleFilesIn(folderTree, subtitleDir),
        )
        _state.update {
            it.copy(folderTreeUri = folderTree.toString(), tracks = tracks, writable = writable)
        }
        var loadedReal = false
        val preferred = autoSelectTrack(tracks)
        if (preferred?.displayName == "同步") {
            loadTrack(preferred)
            loadedReal = _state.value.cues.isNotEmpty()
        } else {
            for (track in tracks) {
                val format = subtitleFormatOf(track.fileName) ?: continue
                val cues = subtitles.readCues(Uri.parse(track.documentUri), format)
                if (cues.isNotEmpty()) {
                    _state.update { it.copy(selectedTrack = track, cues = cues, timelineMode = false) }
                    loadTags(track, cues, resetFilter = true)
                    rehomeTimelineOnto(track, cues)
                    loadedReal = true
                    break
                }
            }
        }
        if (!loadedReal) enterTimeline()
        if (writable) cloudCheck(resolvedName)
        refreshScreenshots()
        viewModelScope.launch {
            withContext(NonCancellable) { syncScreenshots() }
        }
        performLeaveSync()
    }

    private suspend fun cloudCheck(resolvedName: String) {
        val tree = contentTreeUri ?: return
        val directory = contentDirectoryUri ?: return
        try {
            val result = cloud.openSync(
                mediaUri = Uri.parse(mediaUriString),
                mediaName = resolvedName,
                treeUri = tree,
                directoryUri = directory,
            ) { prompt -> awaitCloudPrompt(prompt) }
            if (!result.videoHash.isNullOrBlank()) videoHash = result.videoHash
            if (result.subtitlesChanged || result.tagsChanged) reloadSubtitles(resolvedName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun awaitCloudPrompt(prompt: CloudPrompt): CloudAnswer {
        val deferred = kotlinx.coroutines.CompletableDeferred<CloudAnswer>()
        cloudAnswer = deferred
        _state.update { it.copy(cloudPrompt = prompt) }
        return try {
            deferred.await()
        } finally {
            if (cloudAnswer === deferred) cloudAnswer = null
            _state.update { it.copy(cloudPrompt = null) }
        }
    }

    private suspend fun reloadSubtitles(resolvedName: String) {
        val tree = contentTreeUri ?: return
        val directory = contentDirectoryUri ?: return
        val tracks = findSubtitlesForMedia(
            resolvedName,
            library.listSubtitleFilesIn(tree, directory),
        )
        val previous = _state.value.selectedTrack?.fileName
        if (tracks.isEmpty()) {
            enterTimeline()
            return
        }
        _state.update { it.copy(tracks = tracks, timelineMode = false) }
        val next = tracks.find { it.fileName == previous && !isTimelineSubtitleFile(resolvedMediaName, it.fileName) }
            ?: autoSelectTrack(tracks)
            ?: return
        loadTrack(next)
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
        _state.update { it.copy(selectedTrack = track, cues = cues, message = null, timelineMode = false) }
        loadTags(track, cues, resetFilter = true)
        rehomeTimelineOnto(track, cues)
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
        val currentName = _state.value.selectedTrack?.fileName
        val ordered = withContext(Dispatchers.IO) {
            val directoryId = try {
                android.provider.DocumentsContract.getDocumentId(directory)
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            val names = library.listFolder(tree, directoryId).map { it.displayName }
            val companions = companionSubtitleNames(mediaName, names)
            buildList {
                if (!currentName.isNullOrBlank()) add(currentName)
                companions.filter { it != currentName }.forEach { add(it) }
            }
        }
        val documents = ordered.mapNotNull { name -> tagDocuments.readInFolder(tree, directory, name) }
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

    private suspend fun persistAlignment(alignment: TagAlignment, fromUser: Boolean = false): Boolean {
        val track = _state.value.selectedTrack ?: return false
        val tree = contentTreeUri ?: return false
        val directory = contentDirectoryUri ?: return false
        val base = com.jianqiaofan.subtitleplayer.domain.tags.alignmentToDocument(track.fileName, alignment)
        val entries = if (_state.value.timelineMode) {
            timelineEntriesForSave(base?.entries.orEmpty(), timelineHiddenEntries)
        } else {
            base?.entries.orEmpty()
        }
        val document = if (entries.isEmpty()) {
            null
        } else {
            com.jianqiaofan.subtitleplayer.domain.tags.TagDocument(
                com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION,
                track.fileName,
                entries,
            )
        }
        val result = tagDocuments.saveInFolder(tree, directory, track.fileName, document)
        if (result.isSuccess && fromUser) dirtyTags += track.fileName
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

    private suspend fun enterTimeline() {
        val fileName = timelineSubtitleFileName(mediaStem(resolvedMediaName))
        timelineHiddenEntries = emptyList()
        _state.update {
            it.copy(
                timelineMode = true,
                tracks = emptyList(),
                selectedTrack = SubtitleTrack("", fileName, "时间线"),
                cues = emptyList(),
                message = null,
            )
        }
        publishTimeline(player.duration.takeIf { it > 0 } ?: _state.value.durationMs)
    }

    private suspend fun publishTimeline(durationMs: Long) {
        if (!_state.value.timelineMode || durationMs <= 0L || _state.value.cues.isNotEmpty()) return
        val step = if (timelineStepChosen) {
            _state.value.timelineStepSec
        } else {
            defaultTimelineStepSeconds(durationMs / 1000.0)
        }
        rebuildTimeline(step)
    }

    private suspend fun rebuildTimeline(step: Int) {
        val durationMs = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        if (durationMs <= 0L) {
            _state.update { it.copy(timelineStepSec = step) }
            return
        }
        val cues = buildTimeline(durationMs / 1000.0, step)
        val document = readTimelineDocument()
        val (attached, hidden) = alignTimelineTags(cues, document?.entries.orEmpty())
        timelineHiddenEntries = hidden
        _state.update { it.copy(timelineStepSec = step, cues = cues, message = null) }
        publishAlignment(com.jianqiaofan.subtitleplayer.domain.tags.TagAlignment(attached), com.jianqiaofan.subtitleplayer.domain.tags.TagListFilter())
    }

    private suspend fun readTimelineDocument(): com.jianqiaofan.subtitleplayer.domain.tags.TagDocument? {
        val tree = contentTreeUri ?: return null
        val directory = contentDirectoryUri ?: return null
        return tagDocuments.readInFolder(tree, directory, timelineSubtitleFileName(mediaStem(resolvedMediaName)))
    }

    private suspend fun rehomeTimelineOnto(track: SubtitleTrack, cues: List<com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue>) {
        if (isTimelineSubtitleFile(resolvedMediaName, track.fileName) || cues.isEmpty()) return
        val tree = contentTreeUri ?: return
        val directory = contentDirectoryUri ?: return
        val timelineName = timelineSubtitleFileName(mediaStem(resolvedMediaName))
        val timelineDoc = tagDocuments.readInFolder(tree, directory, timelineName) ?: return
        val subtitleDoc = tagDocuments.readInFolder(tree, directory, track.fileName)
        val result = rehomeTimelineTags(timelineDoc.entries, cues, subtitleDoc?.entries.orEmpty(), utcTimestamp())
        if (result.movedIds.isEmpty()) return
        val version = com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION
        val savedSubtitle = tagDocuments.saveInFolder(
            tree,
            directory,
            track.fileName,
            com.jianqiaofan.subtitleplayer.domain.tags.TagDocument(version, track.fileName, result.subtitleEntries),
        )
        tagDocuments.saveInFolder(
            tree,
            directory,
            timelineName,
            com.jianqiaofan.subtitleplayer.domain.tags.TagDocument(version, timelineName, result.timelineEntries),
        )
        if (savedSubtitle.isSuccess) {
            dirtyTags += track.fileName
            dirtyTags += timelineName
            loadTags(track, cues, resetFilter = false)
        }
    }

    private suspend fun startStudySession() {
        val directory = contentDirectoryUri ?: return
        val username = prefs.cloudAccountOnce().username
        val id = newPlaybackSessionId()
        val started = System.currentTimeMillis()
        val opened = studyMutex.withLock {
            if (openSessionId != null) return@withLock false
            openSessionId = id
            withContext(Dispatchers.IO) {
                val log = bundles.readPlayback(directory)
                bundles.writePlayback(directory, beginSession(log, id, started))
            }
            true
        }
        if (!opened) return
        if (username.isNotBlank()) {
            prefs.updateStudyMemory { it.copy(sessionOwners = it.sessionOwners + (id to username)) }
        }
        syncStudyLog()
    }

    private suspend fun finishStudySession() {
        val directory = contentDirectoryUri ?: return
        val id = studyMutex.withLock {
            val current = openSessionId ?: return@withLock null
            openSessionId = null
            current
        } ?: return
        val ended = System.currentTimeMillis()
        studyMutex.withLock {
            withContext(Dispatchers.IO) {
                val log = bundles.readPlayback(directory)
                bundles.writePlayback(directory, closeSession(log, id, ended))
            }
        }
        syncStudyLog()
    }

    private suspend fun syncStudyLog() {
        val hash = videoHash ?: return
        val directory = contentDirectoryUri ?: return
        val username = prefs.cloudAccountOnce().username
        if (username.isBlank() || !DeviceNetwork.isOnline(getApplication())) return
        val memory = prefs.studyMemory()
        val upload = studyMutex.withLock {
            val log = withContext(Dispatchers.IO) { bundles.readPlayback(directory) }
            sessionsToUpload(log.sessions, memory.sessionOwners, username, openSessionId)
        }
        val remote = cloud.syncPlayback(hash, mediaStem(resolvedMediaName), upload) ?: return
        studyMutex.withLock {
            val log = withContext(Dispatchers.IO) { bundles.readPlayback(directory) }
            withContext(Dispatchers.IO) {
                bundles.writePlayback(directory, PlaybackLog(sessions = mergePlaybackSessions(log.sessions, remote)))
            }
        }
        val owned = remote.map { it.id } + upload.map { it.id }
        prefs.updateStudyMemory { current ->
            current.copy(sessionOwners = current.sessionOwners + owned.associateWith { username })
        }
    }

    private suspend fun performLeaveSync() {
        val existing = leaveGate
        if (existing != null) {
            existing.await()
            return
        }
        if (leaveSettled && dirtySubtitles.isEmpty() && dirtyTags.isEmpty()) return
        val gate = CompletableDeferred<Unit>()
        leaveGate = gate
        try {
            val username = prefs.cloudAccountOnce().username
            val online = DeviceNetwork.isOnline(getApplication())
            val memory = prefs.studyMemory()
            val pending = storedPending(memory).merge(
                PendingChanges(dirtySubtitles.isNotEmpty(), dirtyTags.isNotEmpty()),
            )
            val policy = policyFor(memory)
            when (leaveAction(username.isNotBlank(), online, pending, policy)) {
                LeaveAction.Skip -> {
                    if (pending.any) savePending(pending) else leaveSettled = true
                }
                LeaveAction.Auto -> {
                    val upload = itemsToUpload(policy!!, pending)
                    val error = uploadPending(upload)
                    if (error.isNotBlank()) {
                        savePending(pending)
                    } else {
                        if (upload.subtitles || !policy.syncSubtitles) dirtySubtitles.clear()
                        if (upload.tags || !policy.syncTags) dirtyTags.clear()
                        savePending(PendingChanges())
                        leaveSettled = true
                    }
                }
                LeaveAction.Ask -> {
                    val choice = askLeave(pending)
                    var next = pending
                    if (choice.sync) {
                        val upload = PendingChanges(
                            subtitles = choice.subtitles && pending.subtitles,
                            tags = choice.tags && pending.tags,
                        )
                        val error = uploadPending(upload)
                        if (error.isBlank()) {
                            if (upload.subtitles) dirtySubtitles.clear()
                            if (upload.tags) dirtyTags.clear()
                            next = PendingChanges(
                                subtitles = pending.subtitles && !upload.subtitles,
                                tags = pending.tags && !upload.tags,
                            )
                        } else {
                            _state.update { it.copy(message = error) }
                        }
                    }
                    if (choice.remember) {
                        savePolicy(LeavePolicy(choice.subtitles, choice.tags))
                        if (!choice.subtitles) dirtySubtitles.clear()
                        if (!choice.tags) dirtyTags.clear()
                        next = PendingChanges(
                            subtitles = next.subtitles && choice.subtitles,
                            tags = next.tags && choice.tags,
                        )
                    }
                    savePending(next)
                    leaveSettled = true
                }
            }
        } finally {
            gate.complete(Unit)
            if (leaveGate === gate) leaveGate = null
        }
    }

    private suspend fun askLeave(pending: PendingChanges): LeaveChoice {
        val deferred = CompletableDeferred<LeaveChoice>()
        leaveAnswer = deferred
        _state.update {
            it.copy(leavePrompt = LeaveSyncUi(pending.subtitles, pending.tags))
        }
        return try {
            deferred.await()
        } finally {
            if (leaveAnswer === deferred) leaveAnswer = null
            _state.update { it.copy(leavePrompt = null) }
        }
    }

    private suspend fun uploadPending(upload: PendingChanges): String {
        if (!upload.any) return ""
        val hash = videoHash ?: return "还没算好视频哈希，请稍后再同步。"
        val tree = contentTreeUri ?: return ""
        val directory = contentDirectoryUri ?: return ""
        return cloud.uploadEdits(
            videoHash = hash,
            videoStem = mediaStem(resolvedMediaName),
            treeUri = tree,
            directoryUri = directory,
            subtitleNames = if (upload.subtitles) subtitleNamesForUpload() else emptyList(),
            tagSubtitleNames = if (upload.tags) tagNamesForUpload() else emptyList(),
        )
    }

    private fun subtitleNamesForUpload(): List<String> {
        if (dirtySubtitles.isNotEmpty()) return dirtySubtitles.toList()
        val tree = contentTreeUri ?: return emptyList()
        val directory = contentDirectoryUri ?: return emptyList()
        return library.listSubtitleFilesIn(tree, directory).map { it.fileName }
    }

    private fun tagNamesForUpload(): List<String> {
        if (dirtyTags.isNotEmpty()) return dirtyTags.toList()
        val tree = contentTreeUri ?: return emptyList()
        val directory = contentDirectoryUri ?: return emptyList()
        val directoryId = try {
            android.provider.DocumentsContract.getDocumentId(directory)
        } catch (_: Exception) {
            return emptyList()
        }
        return library.listFolder(tree, directoryId).mapNotNull { child ->
            subtitleFileNameFromTagFile(child.displayName)
        }
    }

    private fun storedPending(memory: StudySyncMemory): PendingChanges =
        syncKeys().fold(PendingChanges()) { acc, key ->
            acc.merge(memory.pending[key] ?: PendingChanges())
        }

    private fun policyFor(memory: StudySyncMemory): LeavePolicy? =
        syncKeys().firstNotNullOfOrNull { memory.policies[it] }

    private fun syncKeys(): List<String> = listOfNotNull(videoHash?.takeIf { it.isNotBlank() }, mediaUriString).distinct()

    private suspend fun savePending(pending: PendingChanges) {
        val key = videoHash?.takeIf { it.isNotBlank() } ?: mediaUriString
        prefs.updateStudyMemory { memory ->
            val pendingMap = memory.pending.toMutableMap()
            syncKeys().forEach { pendingMap.remove(it) }
            if (pending.any) pendingMap[key] = pending
            memory.copy(pending = pendingMap)
        }
    }

    private suspend fun savePolicy(policy: LeavePolicy) {
        val key = videoHash?.takeIf { it.isNotBlank() } ?: mediaUriString
        prefs.updateStudyMemory { memory ->
            memory.copy(policies = memory.policies + (key to policy))
        }
    }

    private suspend fun screenshotBundle(): Pair<Uri, Uri>? {
        val tree = contentTreeUri ?: return null
        val directory = contentDirectoryUri ?: return null
        val name = withContext(Dispatchers.IO) { library.displayNameOf(directory) } ?: return null
        if (!isBundleFolderName(name)) return null
        return tree to directory
    }

    private suspend fun refreshScreenshots() {
        val bundle = screenshotBundle()
        if (bundle == null) {
            _state.update { it.copy(screenshots = emptyList()) }
            return
        }
        val shots = screenshotsRepo.read(bundle.first, bundle.second)
        _state.update { it.copy(screenshots = shots) }
    }

    private suspend fun syncScreenshots() {
        val bundle = screenshotBundle() ?: return
        if (!_state.value.writable) return
        val message = screenshotsRepo.sync(
            bundle.first,
            bundle.second,
            Uri.parse(mediaUriString),
            resolvedMediaName,
            mediaUriString,
            videoHash,
        )
        refreshScreenshots()
        if (!message.isNullOrBlank()) _state.update { it.copy(message = message) }
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
