package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.jianqiaofan.subtitleplayer.R
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSideLandscape
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSidePortrait
import com.jianqiaofan.subtitleplayer.domain.display.immersiveUnavailableReason
import com.jianqiaofan.subtitleplayer.domain.display.isImmersiveListAvailable
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.subtitle.PLAYBACK_SPEEDS
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import com.jianqiaofan.subtitleplayer.ui.ApplyPreferredOrientation
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.VideoBlack
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground
import com.jianqiaofan.subtitleplayer.ui.toggledFrom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val SubtitleFollowResumeMs = 2_000L

private val VideoViewportSaver = Saver<VideoViewport, List<Float>>(
    save = { listOf(it.scale, it.offsetX, it.offsetY) },
    restore = { VideoViewport(it[0], it[1], it[2]) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    mediaUri: String,
    mediaName: String,
    onBack: () -> Unit,
    onEdit: (Int) -> Unit,
    onOpenMedia: (String, String) -> Unit = { _, _ -> },
    onBrowseMedia: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val sleepShutdown: SleepShutdownViewModel = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
    )
    val sleepState by sleepShutdown.state.collectAsStateWithLifecycle()
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(app, mediaUri, mediaName),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tagFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        com.jianqiaofan.subtitleplayer.data.OpenTagDocuments(),
    ) { uris -> viewModel.syncTagFiles(uris) }
    val configuration = LocalConfiguration.current
    val devicePortrait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    val sideBySide = !devicePortrait
    val display = state.displaySettings
    var settingsPreview by remember { mutableStateOf<PlayerDisplaySettings?>(null) }
    val effectiveDisplay = settingsPreview ?: display

    ApplyPreferredOrientation(display.preferredOrientation)

    val immersiveAvailable = isImmersiveListAvailable(
        state.videoWidth,
        state.videoHeight,
        devicePortrait,
    )
    val immersiveActive = effectiveDisplay.immersiveList && immersiveAvailable && state.showSubtitleList
    val immersiveChrome = Color.Black.copy(alpha = 0.45f)
    val view = LocalView.current
    DisposableEffect(immersiveActive, state.playing) {
        val activity = context as? Activity
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val fullscreen = immersiveActive && state.playing
        if (fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val immersiveReason = immersiveUnavailableReason(
        state.videoWidth,
        state.videoHeight,
        devicePortrait,
    )

    LaunchedEffect(effectiveDisplay.immersiveList, immersiveAvailable, settingsPreview) {
        if (settingsPreview == null && effectiveDisplay.immersiveList && !immersiveAvailable) {
            viewModel.setImmersiveListEnabled(false)
            immersiveReason?.let { viewModel.showTransientMessage(it) }
        }
    }

    var splitFraction by rememberSaveable { mutableFloatStateOf(if (sideBySide) 0.58f else 0.60f) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var showCountdown by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    var explainSync by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    var tagEditIndices by remember { mutableStateOf<List<Int>?>(null) }
    var noteCue by remember { mutableStateOf<Int?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }

    LaunchedEffect(state.message, state.playError) {
        val text = state.playError ?: state.message
        if (!text.isNullOrBlank()) {
            if ('\n' in text) reportText = text else snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    var followPaused by remember { mutableStateOf(false) }
    val userScrollGeneration = remember { mutableIntStateOf(0) }
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollGeneration.intValue += 1
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.y != 0f) {
                    userScrollGeneration.intValue += 1
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(userScrollGeneration.intValue) {
        if (userScrollGeneration.intValue == 0) return@LaunchedEffect
        followPaused = true
        delay(50)
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        delay(SubtitleFollowResumeMs)
        followPaused = false
    }
    val listRows = com.jianqiaofan.subtitleplayer.domain.tags.buildListRows(
        state.cues.size,
        com.jianqiaofan.subtitleplayer.domain.tags.TagAlignment(state.attachedTags, state.unmatchedTags),
        state.tagFilter,
    )
    val followRow = listRows.indexOfFirst { row ->
        row is com.jianqiaofan.subtitleplayer.domain.tags.SubtitleListRow.Cue && row.cueIndex == state.currentCueIndex
    }
    LaunchedEffect(followRow, followPaused) {
        if (!followPaused && followRow >= 0) {
            listState.centerItem(followRow)
        }
    }

    val captionSpan = com.jianqiaofan.subtitleplayer.domain.display.onScreenHorizontalSpan(
        immersiveListVisible = immersiveActive,
        coversHorizontal = !devicePortrait,
        listOnLeft = effectiveDisplay.immersiveListSideLandscape ==
            com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSideLandscape.Left,
        listFraction = effectiveDisplay.immersiveListSizePercent / 100f,
    )

    val onscreenText = state.cues.getOrNull(state.currentCueIndex)?.text

    val listContent: @Composable (Color, Boolean) -> Unit = { panelBg, showHeader ->
        SubtitleListPane(
            cues = state.cues,
            currentCueIndex = state.currentCueIndex,
            tracksEmpty = state.tracks.isEmpty(),
            density = display.subtitleListDensity,
            listState = listState,
            userScrollConnection = userScrollConnection,
            selectionMode = selectionMode,
            selectedIndices = selectedIndices,
            menuIndex = menuIndex,
            onMenuIndexChange = { menuIndex = it },
            onDensityToggle = {
                viewModel.updateDisplaySettings {
                    it.copy(subtitleListDensity = it.subtitleListDensity.toggled())
                }
            },
            onCueClick = { index ->
                userScrollGeneration.intValue = 0
                followPaused = false
                viewModel.seekToCue(index)
            },
            onEnterSelection = { index ->
                selectionMode = true
                selectedIndices = setOf(index)
            },
            onToggleSelection = { index ->
                selectedIndices = if (index in selectedIndices) {
                    selectedIndices - index
                } else {
                    selectedIndices + index
                }
            },
            onExitSelection = {
                selectionMode = false
                selectedIndices = emptySet()
            },
            onRepeat = viewModel::startRepeat,
            onEdit = onEdit,
            attached = state.attachedTags,
            unmatched = state.unmatchedTags,
            tagFilter = state.tagFilter,
            onTagFilter = viewModel::setTagFilter,
            onJumpTagged = viewModel::jumpTagged,
            onNoteClick = { noteCue = it },
            onTag = { tagEditIndices = it },
            onClearTags = viewModel::clearCueTags,
            onUnmatchedClick = { entry ->
                viewModel.seekTo((entry.start * 1000.0).toLong(), fromUser = true)
                if (!state.playing) viewModel.togglePlayPause()
            },
            onAttachUnmatched = viewModel::attachUnmatched,
            onDeleteUnmatched = viewModel::deleteUnmatched,
            panelBackground = panelBg,
            showHeader = showHeader,
        )
    }

    if (showBatch) {
        com.jianqiaofan.subtitleplayer.ui.player.BatchTagSyncScreen(
            onBack = { showBatch = false },
            onApplied = { viewModel.reloadTagsFromDisk() },
        )
        return
    }

    val playerTopBar: @Composable (Color, Boolean) -> Unit = { barColor, overlay ->
        TopAppBar(
            windowInsets = if (overlay) WindowInsets(0.dp) else TopAppBarDefaults.windowInsets,
            title = {
                Text(
                    state.mediaName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    viewModel.pauseForNavigation()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                com.jianqiaofan.subtitleplayer.ui.OpenFileMenuButton(
                    recents = state.recentMedia,
                    onOpenPicker = onBrowseMedia,
                    onOpenRecent = { item -> onOpenMedia(item.uri, item.displayName) },
                )
                Box {
                    IconButton(onClick = { toolsMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("同步标签文件") },
                            onClick = { toolsMenu = false; explainSync = true },
                        )
                        DropdownMenuItem(
                            text = { Text("批量同步标签") },
                            onClick = { toolsMenu = false; showBatch = true },
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val next = display.preferredOrientation.toggledFrom(devicePortrait)
                        viewModel.updateDisplaySettings {
                            it.copy(preferredOrientation = next)
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = if (devicePortrait) "切换横屏" else "切换竖屏",
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Subtitles, contentDescription = "画面字幕")
                }
                TextButton(onClick = { subtitleMenu = true }) {
                    Text(state.selectedTrack?.displayName ?: "未找到字幕")
                }
                DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    if (state.tracks.isEmpty()) {
                        DropdownMenuItem(text = { Text("未找到字幕") }, onClick = { subtitleMenu = false })
                    } else {
                        state.tracks.forEach { track ->
                            DropdownMenuItem(
                                text = { Text(track.displayName) },
                                onClick = {
                                    viewModel.selectTrack(track)
                                    subtitleMenu = false
                                },
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        contentWindowInsets = if (immersiveActive) {
            WindowInsets(0.dp)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!immersiveActive) playerTopBar(SurfacePanel, false)
        },
        bottomBar = {
            if (!immersiveActive) {
                PlayerControlsBar(
                    playing = state.playing,
                    muted = state.muted,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    speed = state.speed,
                    countdownLabel = state.countdownLabel,
                    sleepLabel = sleepState.label,
                    speedMenu = speedMenu,
                    onSpeedMenu = { speedMenu = it },
                    onTogglePlay = viewModel::togglePlayPause,
                    onToggleMute = viewModel::toggleMute,
                    onSeek = { viewModel.seekTo(it, fromUser = true) },
                    onSpeed = viewModel::setSpeed,
                    onCountdown = { showCountdown = true },
                    onSleep = { showSleep = true },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (!state.showSubtitleList) {
                VideoPane(
                    player = viewModel.player,
                    isAudio = state.isAudio,
                    playing = state.playing,
                    sideBySide = sideBySide,
                    onscreenText = onscreenText,
                    displaySettings = effectiveDisplay,
                    horizontalSpan = com.jianqiaofan.subtitleplayer.domain.display.OnScreenHorizontalSpan.Full,
                    onTogglePlay = viewModel::togglePlayPause,
                    onRotate = {
                        val next = display.preferredOrientation.toggledFrom(devicePortrait)
                        viewModel.updateDisplaySettings { it.copy(preferredOrientation = next) }
                    },
                    onSubtitleSettings = { showSettings = true },
                )
            } else if (immersiveActive) {
                ImmersivePlayerLayout(
                    devicePortrait = devicePortrait,
                    sizePercent = effectiveDisplay.immersiveListSizePercent,
                    sideLandscape = effectiveDisplay.immersiveListSideLandscape,
                    sidePortrait = effectiveDisplay.immersiveListSidePortrait,
                    listOpacity = effectiveDisplay.immersiveListOpacity,
                    onSizePercentChange = { percent ->
                        val preview = settingsPreview
                        if (preview != null) {
                            settingsPreview = preview.copy(immersiveListSizePercent = percent)
                        } else {
                            viewModel.updateDisplaySettings {
                                it.copy(immersiveListSizePercent = percent)
                            }
                        }
                    },
                    video = {
                        VideoPane(
                            player = viewModel.player,
                            isAudio = state.isAudio,
                            playing = state.playing,
                            sideBySide = sideBySide,
                            onscreenText = onscreenText,
                            displaySettings = effectiveDisplay,
                            horizontalSpan = captionSpan,
                            onTogglePlay = viewModel::togglePlayPause,
                            showPauseOverlays = false,
                            onRotate = {
                                val next = display.preferredOrientation.toggledFrom(devicePortrait)
                                viewModel.updateDisplaySettings { it.copy(preferredOrientation = next) }
                            },
                            onSubtitleSettings = { showSettings = true },
                        )
                    },
                    list = { listContent(Color.Transparent, true) },
                )
            } else {
                SplitPaneLayout(
                    modifier = Modifier.fillMaxSize(),
                    sideBySide = sideBySide,
                    fraction = splitFraction,
                    onFractionChange = { splitFraction = it.coerceIn(0.28f, 0.75f) },
                    primary = {
                        VideoPane(
                            player = viewModel.player,
                            isAudio = state.isAudio,
                            playing = state.playing,
                            sideBySide = sideBySide,
                            onscreenText = onscreenText,
                            displaySettings = effectiveDisplay,
                            horizontalSpan = com.jianqiaofan.subtitleplayer.domain.display.OnScreenHorizontalSpan.Full,
                            onTogglePlay = viewModel::togglePlayPause,
                            onRotate = {
                                val next = display.preferredOrientation.toggledFrom(devicePortrait)
                                viewModel.updateDisplaySettings { it.copy(preferredOrientation = next) }
                            },
                            onSubtitleSettings = { showSettings = true },
                        )
                    },
                    secondary = {
                        listContent(SurfacePanel, true)
                    },
                )
            }
            if (immersiveActive && !state.playing) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) {
                    playerTopBar(immersiveChrome, true)
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    PlayerControlsBar(
                        playing = state.playing,
                        muted = state.muted,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        speed = state.speed,
                        countdownLabel = state.countdownLabel,
                        sleepLabel = sleepState.label,
                        speedMenu = speedMenu,
                        onSpeedMenu = { speedMenu = it },
                        onTogglePlay = viewModel::togglePlayPause,
                        onToggleMute = viewModel::toggleMute,
                        onSeek = { viewModel.seekTo(it, fromUser = true) },
                        onSpeed = viewModel::setSpeed,
                        onCountdown = { showCountdown = true },
                        onSleep = { showSleep = true },
                        background = immersiveChrome,
                    )
                }
            }
        }
    }

    if (showCountdown) {
        CountdownDialog(
            onDismiss = { showCountdown = false },
            onConfirm = { minutes ->
                viewModel.startCountdown(minutes)
                showCountdown = false
            },
        )
    }

    if (showSleep) {
        SleepShutdownDialog(
            active = sleepState.active,
            onDismiss = { showSleep = false },
            onStart = { minutes ->
                sleepShutdown.start(minutes)
                showSleep = false
            },
            onCancelTimer = {
                sleepShutdown.cancel()
                showSleep = false
            },
        )
    }

    if (showSettings) {
        com.jianqiaofan.subtitleplayer.ui.settings.OnScreenSubtitleSettingsDialog(
            saved = display,
            showSubtitleList = state.showSubtitleList,
            immersiveAvailable = immersiveAvailable,
            immersiveUnavailableReason = immersiveReason,
            devicePortrait = devicePortrait,
            onPreview = { settingsPreview = it },
            onShowSubtitleList = viewModel::setShowSubtitleList,
            onConfirm = { next ->
                viewModel.updateDisplaySettings { next }
                settingsPreview = null
                showSettings = false
            },
            onDismiss = {
                settingsPreview = null
                showSettings = false
            },
        )
    }
    if (explainSync) {
        SyncTagsExplainDialog(
            onDismiss = { explainSync = false },
            onContinue = {
                explainSync = false
                val initial = state.folderTreeUri?.let { android.net.Uri.parse(it) }
                tagFilePicker.launch(initial)
            },
        )
    }
    val editing = tagEditIndices
    if (editing != null) {
        val single = editing.size == 1
        val entry = editing.singleOrNull()?.let { state.attachedTags[it] }
        TagEditDialog(
            single = single,
            initialTags = if (single) entry?.tags.orEmpty() else emptyList(),
            initialNote = if (single) entry?.note.orEmpty() else "",
            customNames = state.customTagNames,
            onDismiss = { tagEditIndices = null },
            onConfirm = { tags, note, applyNote ->
                viewModel.applyTags(
                    com.jianqiaofan.subtitleplayer.domain.tags.TagEdit(
                        cueIndices = editing,
                        tags = tags,
                        note = note,
                        applyNote = applyNote,
                    ),
                )
                tagEditIndices = null
            },
        )
    }
    noteCue?.let { index ->
        NoteEditDialog(
            note = state.attachedTags[index]?.note.orEmpty(),
            onDismiss = { noteCue = null },
            onSave = { text ->
                viewModel.saveCueNote(index, text)
                noteCue = null
            },
        )
    }
    reportText?.let { text ->
        AlertDialog(
            onDismissRequest = { reportText = null },
            title = { Text("同步结果") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { reportText = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ImmersivePlayerLayout(
    devicePortrait: Boolean,
    sizePercent: Int,
    sideLandscape: ImmersiveListSideLandscape,
    sidePortrait: ImmersiveListSidePortrait,
    listOpacity: Float,
    onSizePercentChange: (Int) -> Unit,
    video: @Composable () -> Unit,
    list: @Composable () -> Unit,
) {
    val listBg = Color.Black.copy(alpha = listOpacity.coerceIn(0f, 1f))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        video()
        val fraction = (sizePercent / 100f).coerceIn(0.18f, 0.70f)
        if (devicePortrait) {
            val listH = (maxHeight * fraction)
            val top = sidePortrait == ImmersiveListSidePortrait.Top
            val dragState = rememberDraggableState { delta ->
                val total = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val current = total * fraction
                val next = if (top) current + delta else current - delta
                onSizePercentChange(((next / total) * 100f).toInt().coerceIn(18, 70))
            }
            Box(
                modifier = Modifier
                    .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(listH)
                    .background(listBg),
            ) {
                list()
                SplitHandle(
                    Orientation.Vertical,
                    Modifier
                        .align(if (top) Alignment.BottomCenter else Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(14.dp)
                        .draggable(dragState, Orientation.Vertical),
                )
            }
        } else {
            val listW = maxWidth * fraction
            val left = sideLandscape == ImmersiveListSideLandscape.Left
            val dragState = rememberDraggableState { delta ->
                val total = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val current = total * fraction
                val next = if (left) current + delta else current - delta
                onSizePercentChange(((next / total) * 100f).toInt().coerceIn(18, 70))
            }
            Box(
                modifier = Modifier
                    .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(listW)
                    .background(listBg),
            ) {
                list()
                SplitHandle(
                    Orientation.Horizontal,
                    Modifier
                        .align(if (left) Alignment.CenterEnd else Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(14.dp)
                        .draggable(dragState, Orientation.Horizontal),
                )
            }
        }
    }
}

@Composable
private fun VideoPane(
    player: androidx.media3.exoplayer.ExoPlayer,
    isAudio: Boolean,
    playing: Boolean,
    sideBySide: Boolean,
    onscreenText: String?,
    displaySettings: com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings,
    horizontalSpan: com.jianqiaofan.subtitleplayer.domain.display.OnScreenHorizontalSpan,
    onTogglePlay: () -> Unit,
    showPauseOverlays: Boolean = true,
    onRotate: () -> Unit,
    onSubtitleSettings: () -> Unit,
) {
    var viewport by rememberSaveable(stateSaver = VideoViewportSaver) { mutableStateOf(VideoViewport()) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    val onTapAction by rememberUpdatedState(newValue = onTogglePlay)

    fun applyGesture(centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoom: Float) {
        viewport = applyVideoViewportGesture(
            current = viewport,
            zoom = zoom,
            panX = panX,
            panY = panY,
            centroidX = centroidX,
            centroidY = centroidY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VideoBlack)
            .clipToBounds()
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
                viewport = clampVideoViewport(viewport, viewportWidth, viewportHeight)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isAudio) {
            Text(
                "音频播放中",
                color = AccentPurple,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { onTogglePlay() },
            )
        } else {
            AndroidView(
                factory = { context ->
                    (LayoutInflater.from(context).inflate(R.layout.player_texture_view, null) as PlayerView).apply {
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewport.scale
                        scaleY = viewport.scale
                        translationX = viewport.offsetX
                        translationY = viewport.offsetY
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVideoViewportGestures(
                            canPan = { viewport.scale > VIDEO_MIN_SCALE + 0.01f },
                            onTap = { onTapAction() },
                            onGesture = { centroid, pan, zoom ->
                                applyGesture(centroid.x, centroid.y, pan.x, pan.y, zoom)
                            },
                        )
                    },
            )
        }
        OnScreenSubtitleOverlay(
            text = onscreenText,
            settings = displaySettings,
            horizontalSpan = horizontalSpan,
            modifier = Modifier.fillMaxSize(),
        )
        if (!playing && showPauseOverlays) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = AccentPurple,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        if (!playing && sideBySide && showPauseOverlays) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onRotate) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = "切换竖屏",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onSubtitleSettings) {
                    Icon(
                        Icons.Filled.Subtitles,
                        contentDescription = "画面字幕",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsBar(
    playing: Boolean,
    muted: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    countdownLabel: String,
    sleepLabel: String,
    speedMenu: Boolean,
    onSpeedMenu: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onCountdown: () -> Unit,
    onSleep: () -> Unit,
    background: Color = SurfacePanel,
) {
    var slider by remember { mutableFloatStateOf(positionMs.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(positionMs, dragging) {
        if (!dragging) slider = positionMs.toFloat()
    }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Slider(
            value = slider.coerceIn(0f, max),
            onValueChange = {
                dragging = true
                slider = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(slider.toLong())
            },
            valueRange = 0f..max,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            Text(
                text = "${formatClock(positionMs / 1000.0)} / ${formatClock(durationMs / 1000.0)}",
                style = MaterialTheme.typography.labelMedium,
                color = OnDarkMuted,
            )
            Box {
                TextButton(onClick = { onSpeedMenu(true) }) { Text("${speed}x") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { onSpeedMenu(false) }) {
                    PLAYBACK_SPEEDS.forEach { value ->
                        DropdownMenuItem(
                            text = { Text("${value}x") },
                            onClick = {
                                onSpeed(value)
                                onSpeedMenu(false)
                            },
                        )
                    }
                }
            }
            TextButton(onClick = onCountdown) {
                Icon(Icons.Filled.Timer, contentDescription = "学习倒计时", modifier = Modifier.size(18.dp))
                Text(countdownLabel, modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onSleep) {
                Icon(Icons.Filled.Bedtime, contentDescription = "定时关闭", modifier = Modifier.size(18.dp))
                Text(sleepLabel, modifier = Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                )
            }
        }
    }
}

@Composable
private fun CountdownDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学习倒计时") },
        text = {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("分钟（1～600）") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = minutes.toIntOrNull()?.coerceIn(1, 600) ?: 10
                    onConfirm(value)
                },
            ) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SplitPaneLayout(
    sideBySide: Boolean,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val orientation = if (sideBySide) Orientation.Horizontal else Orientation.Vertical
        val total = if (sideBySide) widthPx else heightPx
        val handlePx = with(density) { 12.dp.toPx() }
        val primaryPx = ((total - handlePx) * fraction).toInt().coerceAtLeast(0)
        val dragState = rememberDraggableState { delta ->
            if (total > handlePx) {
                val current = (total - handlePx) * fraction
                onFractionChange((current + delta) / (total - handlePx))
            }
        }
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(with(density) { primaryPx.toDp() }).fillMaxHeight()) { primary() }
                SplitHandle(
                    orientation,
                    Modifier.width(12.dp).fillMaxHeight().draggable(dragState, orientation),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) { secondary() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(with(density) { primaryPx.toDp() }).fillMaxWidth()) { primary() }
                SplitHandle(
                    orientation,
                    Modifier.height(12.dp).fillMaxWidth().draggable(dragState, orientation),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { secondary() }
            }
        }
    }
}

@Composable
private fun SplitHandle(orientation: Orientation, modifier: Modifier) {
    Box(modifier = modifier.background(WindowBackground.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        val thumb = if (orientation == Orientation.Horizontal) {
            Modifier.width(3.dp).height(36.dp)
        } else {
            Modifier.fillMaxWidth(0.18f).height(3.dp)
        }
        Box(modifier = thumb.clip(RoundedCornerShape(2.dp)).background(AccentPurple.copy(alpha = 0.7f)))
    }
}

internal fun subtitleCenterScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Int {
    val viewportCenter = (viewportStart + viewportEnd) / 2
    val itemCenter = itemOffset + itemSize / 2
    return itemCenter - viewportCenter
}

private suspend fun LazyListState.centerItem(index: Int) {
    if (index < 0) return
    scrollToItem(index)
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = subtitleCenterScrollDelta(
        item.offset,
        item.size,
        layout.viewportStartOffset,
        layout.viewportEndOffset,
    )
    if (delta != 0) {
        scrollBy(delta.toFloat())
    }
}
