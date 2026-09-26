package com.jianqiaofan.subtitleplayer.ui.player

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SleepShutdownState(
    val active: Boolean = false,
    val label: String = "定时关闭",
    val warning: Boolean = false,
    val warningRemainSec: Int = 60,
    val exitNow: Boolean = false,
)

class SleepShutdownViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SleepShutdownState())
    val state: StateFlow<SleepShutdownState> = _state.asStateFlow()
    private var job: Job? = null
    private var promptAt: Long = 0L
    private var exitAt: Long = 0L

    fun start(minutes: Int) {
        val totalMs = minutes.coerceIn(1, 720) * 60_000L
        val now = SystemClock.elapsedRealtime()
        exitAt = now + totalMs
        promptAt = (exitAt - 60_000L).coerceAtLeast(now)
        arm()
    }

    fun snoozeMinutes(minutes: Int) {
        if (!_state.value.active) return
        val now = SystemClock.elapsedRealtime()
        promptAt = now + minutes.coerceIn(1, 180) * 60_000L
        exitAt = promptAt + 60_000L
        _state.update { it.copy(warning = false) }
        arm()
    }

    fun cancel() {
        job?.cancel()
        job = null
        promptAt = 0L
        exitAt = 0L
        _state.value = SleepShutdownState()
    }

    private fun arm() {
        job?.cancel()
        _state.update { it.copy(active = true, exitNow = false, warning = false) }
        job = viewModelScope.launch {
            var shownSec = -1
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val remainMs = exitAt - now
                if (remainMs <= 0L) {
                    _state.update {
                        it.copy(warning = true, warningRemainSec = 0, label = "定时 0:00", exitNow = true)
                    }
                    break
                }
                val remainSec = ((remainMs + 999L) / 1000L).toInt()
                val warning = now >= promptAt
                if (remainSec != shownSec || warning != _state.value.warning) {
                    shownSec = remainSec
                    _state.update {
                        it.copy(
                            active = true,
                            warning = warning,
                            warningRemainSec = if (warning) remainSec else 60,
                            label = "定时 ${formatSleepRemain(remainSec)}",
                        )
                    }
                }
                delay(200)
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}

fun formatSleepRemain(totalSec: Int): String {
    val sec = totalSec.coerceAtLeast(0)
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    val seconds = sec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

val SLEEP_PRESETS = listOf(
    5 to "5分钟",
    15 to "15分钟",
    30 to "30分钟",
    45 to "45分钟",
    60 to "1小时",
    120 to "2小时",
)

val SLEEP_SNOOZE_MINUTES = listOf(5, 15, 30, 45, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepShutdownDialog(
    active: Boolean,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    var custom by remember { mutableStateOf("60") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("到点退出程序，离开这个画面也会继续计时。这和学习倒计时不是同一个功能。到点前 1 分钟会再问一次。")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SLEEP_PRESETS.forEach { (minutes, label) ->
                        TextButton(onClick = { onStart(minutes) }) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("自定义分钟（1～720）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (active) {
                    TextButton(onClick = onCancelTimer) { Text("关闭定时") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minutes = custom.toIntOrNull() ?: return@TextButton
                    onStart(minutes.coerceIn(1, 720))
                },
            ) { Text("自定义开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepWarningDialog(
    remainSec: Int,
    onSnooze: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("即将定时关闭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还剩 ${formatSleepRemain(remainSec)}。没有操作就会退出程序。")
                Text("也可以过一会儿再提示：")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SLEEP_SNOOZE_MINUTES.forEach { minutes ->
                        TextButton(onClick = { onSnooze(minutes) }) { Text("${minutes}分钟") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancelTimer) { Text("关闭定时") }
        },
    )
}
