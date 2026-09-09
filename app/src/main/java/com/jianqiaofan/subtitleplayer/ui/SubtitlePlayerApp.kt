package com.jianqiaofan.subtitleplayer.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jianqiaofan.subtitleplayer.ui.edit.EditSubtitleScreen
import com.jianqiaofan.subtitleplayer.ui.library.LibraryScreen
import com.jianqiaofan.subtitleplayer.ui.player.PlayerScreen
import com.jianqiaofan.subtitleplayer.ui.player.PlayerViewModel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

object Routes {
    const val Library = "library"
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
    Surface(modifier = Modifier.fillMaxSize(), color = WindowBackground) {
        NavHost(navController = navController, startDestination = Routes.Library) {
            composable(Routes.Library) {
                LibraryScreen(
                    onOpenMedia = { uri, name -> navController.navigate(Routes.player(uri, name)) },
                )
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
                    onBack = { navController.popBackStack() },
                    onEdit = { index -> navController.navigate(Routes.edit(uri, name, index)) },
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
}
