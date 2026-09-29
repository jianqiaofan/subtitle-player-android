package com.jianqiaofan.subtitleplayer.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianqiaofan.subtitleplayer.data.ApkInstaller
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.update.AppUpdateViewModel
import java.io.File
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val viewModel: CloudAccountViewModel = viewModel()
    val updateViewModel: AppUpdateViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by updateViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.message, update.message) {
        val accountMessage = state.message
        val updateMessage = update.message
        val message = accountMessage ?: updateMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        if (accountMessage != null) viewModel.consumeMessage()
        if (updateMessage != null) updateViewModel.consumeMessage()
    }
    LaunchedEffect(update.installRequest?.id) {
        val request = update.installRequest ?: return@LaunchedEffect
        val file = File(request.path)
        if (!file.isFile) {
            updateViewModel.consumeInstall("更新包不存在，请重新下载。")
            return@LaunchedEffect
        }
        if (!ApkInstaller.canInstall(context)) {
            try {
                ApkInstaller.openPermissionSettings(context)
                updateViewModel.consumeInstall("请允许本应用安装未知应用，然后再次点「安装更新」。")
            } catch (error: Exception) {
                updateViewModel.consumeInstall("无法打开安装权限设置：${error.message ?: "未知错误"}")
            }
            return@LaunchedEffect
        }
        try {
            ApkInstaller.install(context, file)
            updateViewModel.consumeInstall(null)
        } catch (error: Exception) {
            updateViewModel.consumeInstall("无法打开安装界面：${error.message ?: "未知错误"}")
        }
    }
    Scaffold(
        containerColor = WindowBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfacePanel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "手机和电脑填写同一个用户名，就是同一个用户。视频留在手机上，云端只保存字幕和标签。",
                color = OnDarkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::updateServer,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                text = if (state.loggedInAs.isBlank()) "尚未登录" else "当前登录：${state.loggedInAs}",
                color = AccentPurple,
            )
            if (state.registerBlocked) {
                Text(
                    text = "用户名已存在。修改用户名、密码或服务器地址后才能再次注册。",
                    color = OnDarkMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::register,
                    enabled = !state.busy && !state.registerBlocked,
                ) { Text("注册") }
                OutlinedButton(onClick = viewModel::login, enabled = !state.busy) { Text("登录") }
            }
            TextButton(onClick = {
                if (state.username.isBlank() || state.loggedInAs.isBlank()) {
                    viewModel.remindAccount()
                } else {
                    onOpenLibrary()
                }
            }) { Text("云端管理") }
            OutlinedButton(
                onClick = updateViewModel::check,
                enabled = !state.busy && !update.busy,
            ) {
                Text(if (update.busy) update.status.ifBlank { "请稍候" } else "安装更新")
            }
            Text(
                text = "检查服务器上的新版本。确认后下载，再交给系统安装。",
                color = OnDarkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val offer = update.offer
    if (offer != null) {
        AlertDialog(
            onDismissRequest = updateViewModel::dismissOffer,
            containerColor = SurfacePanel,
            title = { Text("发现新版本 ${offer.versionName}") },
            text = { Text(offer.notes.ifBlank { "可以安装这个更新。" }) },
            confirmButton = {
                TextButton(onClick = updateViewModel::confirmInstall, enabled = !update.busy) {
                    Text("下载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = updateViewModel::dismissOffer, enabled = !update.busy) {
                    Text("取消")
                }
            },
        )
    }
}
