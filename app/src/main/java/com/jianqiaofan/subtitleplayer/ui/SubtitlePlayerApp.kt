package com.jianqiaofan.subtitleplayer.ui

import androidx.activity.ComponentActivity
import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jianqiaofan.subtitleplayer.data.AppPreferences
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.ui.edit.EditSubtitleScreen
import com.jianqiaofan.subtitleplayer.ui.library.FolderPickerScreen
import com.jianqiaofan.subtitleplayer.ui.library.LibraryScreen
import com.jianqiaofan.subtitleplayer.ui.library.MediaBrowserScreen
import com.jianqiaofan.subtitleplayer.ui.player.PlayerScreen
import com.jianqiaofan.subtitleplayer.ui.player.PlayerViewModel
import com.jianqiaofan.subtitleplayer.ui.player.SleepShutdownViewModel
import com.jianqiaofan.subtitleplayer.ui.player.SleepWarningDialog
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

object Routes {
    const val Library = "library"
    const val MediaBrowser = "mediaBrowser"
    const val FolderPicker = "folderPicker"
    const val Player = "player/{mediaUri}/{mediaName}"
    const val Edit = "player/{mediaUri}/{mediaName}/edit/{index}"

    fun player(uri: String, name: String): String =
        "player/${encodeNavArg(uri)}/${encodeNavArg(name)}"

    fun edit(uri: String, name: String, index: Int): String =
        "${player(uri, name)}/edit/$index"
}

@Composable
fun SubtitlePlayerApp() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as Application
    val prefs = remember { AppPreferences(app) }
    val displaySettings by prefs.displaySettings.collectAsStateWithLifecycle(
        initialValue = PlayerDisplaySettings(),
    )
    ApplyPreferredOrientation(displaySettings.preferredOrientation)

    val activity = LocalContext.current as ComponentActivity
    val sleepShutdown: SleepShutdownViewModel = viewModel(viewModelStoreOwner = activity)
    val sleepState by sleepShutdown.state.collectAsStateWithLifecycle()
    LaunchedEffect(sleepState.exitNow) {
        if (sleepState.exitNow) activity.finish()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = WindowBackground) {
        NavHost(navController = navController, startDestination = Routes.Library) {
            composable(Routes.Library) {
                val fromPlayer = navController.previousBackStackEntry?.destination?.route == Routes.Player
                val activity = LocalContext.current as? Activity
                LibraryScreen(
                    onOpenMedia = { uri, name ->
                        navController.navigate(Routes.player(uri, name)) {
                            popUpTo(Routes.Library) { inclusive = false }
                        }
                    },
                    onBrowseMedia = { navController.navigate(Routes.MediaBrowser) },
                    onChooseFolder = { navController.navigate(Routes.FolderPicker) },
                    showBack = fromPlayer,
                    onBack = { navController.popBackStack() },
                    onExit = { activity?.finish() },
                )
            }
            composable(Routes.MediaBrowser) {
                MediaBrowserScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { uri, name ->
                        navController.navigate(Routes.player(uri, name)) {
                            popUpTo(Routes.Library) { inclusive = false }
                        }
                    },
                )
            }
            composable(Routes.FolderPicker) {
                FolderPickerScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.Player,
                arguments = listOf(
                    navArgument("mediaUri") { type = NavType.StringType },
                    navArgument("mediaName") { type = NavType.StringType },
                ),
            ) { entry ->
                val uri = decodeNavArg(entry.arguments?.getString("mediaUri").orEmpty())
                val name = decodeNavArg(entry.arguments?.getString("mediaName").orEmpty())
                PlayerScreen(
                    mediaUri = uri,
                    mediaName = name,
                    onBack = {
                        navController.navigate(Routes.Library) {
                            launchSingleTop = true
                        }
                    },
                    onEdit = { index -> navController.navigate(Routes.edit(uri, name, index)) },
                    onOpenMedia = { nextUri, nextName ->
                        navController.navigate(Routes.player(nextUri, nextName)) {
                            popUpTo(Routes.Player) { inclusive = true }
                        }
                    },
                    onBrowseMedia = { navController.navigate(Routes.MediaBrowser) },
                )
            }
            composable(
                route = Routes.Edit,
                arguments = listOf(
                    navArgument("mediaUri") { type = NavType.StringType },
                    navArgument("mediaName") { type = NavType.StringType },
                    navArgument("index") { type = NavType.IntType },
                ),
            ) { entry ->
                val uri = decodeNavArg(entry.arguments?.getString("mediaUri").orEmpty())
                val name = decodeNavArg(entry.arguments?.getString("mediaName").orEmpty())
                val index = entry.arguments?.getInt("index") ?: 0
                val parent = navController.previousBackStackEntry
                if (parent == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val app = LocalContext.current.applicationContext as Application
                val viewModel: PlayerViewModel = viewModel(
                    viewModelStoreOwner = parent,
                    factory = PlayerViewModel.factory(app, uri, name),
                )
                val state by viewModel.state.collectAsStateWithLifecycle()
                val cue = state.cues.getOrNull(index)
                if (cue == null) {
                    LaunchedEffect(state.cues) {
                        if (state.cues.isNotEmpty()) navController.popBackStack()
                    }
                } else {
                    EditSubtitleScreen(
                        cue = cue,
                        writable = state.writable,
                        onSave = { start, end, text ->
                            viewModel.saveCue(index, start, end, text)
                            navController.popBackStack()
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }
    if (sleepState.warning && !sleepState.exitNow) {
        SleepWarningDialog(
            remainSec = sleepState.warningRemainSec,
            onSnooze = sleepShutdown::snoozeMinutes,
            onCancelTimer = sleepShutdown::cancel,
        )
    }
}
