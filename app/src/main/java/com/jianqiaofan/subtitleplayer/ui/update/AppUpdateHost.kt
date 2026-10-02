package com.jianqiaofan.subtitleplayer.ui.update

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jianqiaofan.subtitleplayer.data.ApkInstaller
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import java.io.File

/** App-wide update dialog, install handoff, and quiet snackbars. */
@Composable
fun BoxScope.AppUpdateHost(viewModel: AppUpdateViewModel) {
    val update by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.checkAuto() }

    LaunchedEffect(update.message) {
        val message = update.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    LaunchedEffect(update.installRequest?.id) {
        val request = update.installRequest ?: return@LaunchedEffect
        val file = File(request.path)
        if (!file.isFile) {
            viewModel.consumeInstall("更新包不存在，请重新下载。")
            return@LaunchedEffect
        }
        if (!ApkInstaller.canInstall(context)) {
            try {
                ApkInstaller.openPermissionSettings(context)
                viewModel.consumeInstall("请允许本应用安装未知应用，然后再次点「安装更新」。")
            } catch (error: Exception) {
                viewModel.consumeInstall("无法打开安装权限设置：${error.message ?: "未知错误"}")
            }
            return@LaunchedEffect
        }
        try {
            ApkInstaller.install(context, file)
            viewModel.consumeInstall(null)
        } catch (error: Exception) {
            viewModel.consumeInstall("无法打开安装界面：${error.message ?: "未知错误"}")
        }
    }

    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
    )

    val offer = update.offer
    if (offer != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOffer,
            containerColor = SurfacePanel,
            title = { Text("发现新版本 ${offer.versionName}") },
            text = { Text(offer.notes.ifBlank { "可以安装这个更新。" }) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmInstall, enabled = !update.busy) {
                    Text("下载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOffer, enabled = !update.busy) {
                    Text("以后再说")
                }
            },
        )
    }
}
