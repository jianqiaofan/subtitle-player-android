package com.jianqiaofan.subtitleplayer.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.time.EditConfirmResult
import com.jianqiaofan.subtitleplayer.domain.time.TimeFields
import com.jianqiaofan.subtitleplayer.domain.time.TimePart
import com.jianqiaofan.subtitleplayer.domain.time.coerceEndAfterStart
import com.jianqiaofan.subtitleplayer.domain.time.confirmEditTimes
import com.jianqiaofan.subtitleplayer.domain.time.nudgeTime
import com.jianqiaofan.subtitleplayer.domain.time.padTimeComponent
import com.jianqiaofan.subtitleplayer.domain.time.parseTimeComponent
import com.jianqiaofan.subtitleplayer.domain.time.secondsToTimeFields
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class FocusedField(val isStart: Boolean, val part: TimePart)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSubtitleScreen(
    cue: SubtitleCue,
    writable: Boolean,
    onSave: (start: Double, end: Double, text: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var start by remember(cue) { mutableStateOf(secondsToTimeFields(cue.start)) }
    var end by remember(cue) { mutableStateOf(secondsToTimeFields(cue.end)) }
    var text by remember(cue) { mutableStateOf(cue.text) }
    var focused by remember { mutableStateOf<FocusedField?>(FocusedField(true, TimePart.Second)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startSecondFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { startSecondFocus.requestFocus() }
    }

    fun applyNudge(part: TimePart, delta: Int, toStart: Boolean) {
        if (toStart) {
            start = nudgeTime(start, part, delta)
            end = coerceEndAfterStart(start, end)
        } else {
            end = nudgeTime(end, part, delta)
        }
    }

    fun nudgeFocused(sign: Int) {
        val target = focused
        if (target == null) {
            scope.launch { snackbar.showSnackbar("请先点选要调的时/分/秒/毫秒") }
            return
        }
        val step = if (target.part == TimePart.Milli) 100 else 1
        applyNudge(target.part, sign * step, target.isStart)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("编辑字幕")
                        Text("第 ${cue.index} 条", style = MaterialTheme.typography.bodySmall, color = OnDarkMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfacePanel),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { nudgeFocused(1) }) { Text("增加") }
                OutlinedButton(onClick = { nudgeFocused(-1) }) { Text("减少") }
            }
            TimeRow(
                label = "起始时间",
                fields = start,
                secondFocusRequester = startSecondFocus,
                onChange = {
                    start = it
                    end = coerceEndAfterStart(it, end)
                },
                onFocus = { part -> focused = FocusedField(true, part) },
                onNudgeSecond = { delta -> applyNudge(TimePart.Second, delta, true) },
            )
            TimeRow(
                label = "终止时间",
                fields = end,
                secondFocusRequester = null,
                onChange = { end = coerceEndAfterStart(start, it) },
                onFocus = { part -> focused = FocusedField(false, part) },
                onNudgeSecond = { delta -> applyNudge(TimePart.Second, delta, false) },
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = { Text("字幕内容") },
            )
            Text(
                text = "若起始时间不早于终止时间，终止时间将自动调整为起始时间 +1 秒。",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkMuted,
            )
            if (!writable) {
                Text("当前目录没有写权限，可以预览但不能保存。", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        when (val result = confirmEditTimes(start, end)) {
                            is EditConfirmResult.Error -> scope.launch { snackbar.showSnackbar(result.message) }
                            is EditConfirmResult.Ok -> {
                                if (!writable) {
                                    scope.launch { snackbar.showSnackbar("当前目录没有写权限，无法保存") }
                                } else {
                                    onSave(result.start, result.end, text)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("确定") }
            }
        }
    }
}

@Composable
private fun TimeRow(
    label: String,
    fields: TimeFields,
    secondFocusRequester: FocusRequester?,
    onChange: (TimeFields) -> Unit,
    onFocus: (TimePart) -> Unit,
    onNudgeSecond: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeBox("时", fields.hours, 2, 3, TimePart.Hour, null, onFocus) { n ->
                onChange(fields.copy(hours = n))
            }
            Spacer(Modifier.width(6.dp))
            TimeBox("分", fields.minutes, 2, 2, TimePart.Minute, null, onFocus) { n ->
                onChange(fields.copy(minutes = n))
            }
            Spacer(Modifier.width(6.dp))
            TimeBox("秒", fields.seconds, 2, 2, TimePart.Second, secondFocusRequester, onFocus) { n ->
                onChange(fields.copy(seconds = n))
            }
            IconButton(onClick = { onNudgeSecond(1) }) { Text("+") }
            IconButton(onClick = { onNudgeSecond(-1) }) { Text("−") }
            TimeBox("毫秒", fields.millis, 3, 3, TimePart.Milli, null, onFocus) { n ->
                onChange(fields.copy(millis = n))
            }
        }
    }
}

@Composable
private fun TimeBox(
    label: String,
    number: Int,
    pad: Int,
    maxLen: Int,
    part: TimePart,
    focusRequester: FocusRequester?,
    onFocus: (TimePart) -> Unit,
    onNumber: (Int) -> Unit,
) {
    val display = padTimeComponent(number, pad)
    var value by remember {
        mutableStateOf(TextFieldValue(display, TextRange(0, display.length)))
    }
    LaunchedEffect(number) {
        if (value.text.toIntOrNull() != number) {
            val padded = padTimeComponent(number, pad)
            value = TextFieldValue(padded, TextRange(padded.length))
        }
    }
    var modifier = Modifier.width(78.dp)
    if (focusRequester != null) modifier = modifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val digits = next.text.filter { it.isDigit() }.take(maxLen)
            value = next.copy(text = digits, selection = TextRange(digits.length))
            parseTimeComponent(digits)?.let(onNumber)
        },
        modifier = modifier.onFocusChanged { focus ->
            if (focus.isFocused) {
                onFocus(part)
                value = value.copy(selection = TextRange(0, value.text.length))
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
