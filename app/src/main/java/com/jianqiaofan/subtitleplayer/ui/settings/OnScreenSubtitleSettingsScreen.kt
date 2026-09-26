package com.jianqiaofan.subtitleplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSideLandscape
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSidePortrait
import com.jianqiaofan.subtitleplayer.domain.display.OnScreenSubtitlePosition
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.display.parseColorArgb
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDark
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

private val PRESET_COLORS = listOf(
    "#FFFFFF", "#FFE082", "#80CBC4", "#90CAF9", "#F48FB1", "#B980FF",
)

@Composable
fun OnScreenSubtitleSettingsDialog(
    saved: PlayerDisplaySettings,
    showSubtitleList: Boolean,
    immersiveAvailable: Boolean,
    immersiveUnavailableReason: String?,
    devicePortrait: Boolean,
    onPreview: (PlayerDisplaySettings) -> Unit,
    onShowSubtitleList: (Boolean) -> Unit,
    onConfirm: (PlayerDisplaySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var askSave by remember { mutableStateOf(false) }
    val listControlsEnabled = showSubtitleList
    val immersiveControlsEnabled = showSubtitleList && draft.immersiveList && immersiveAvailable
    val captionControlsEnabled = draft.onscreenEnabled
    val dirty = draft != saved

    fun closeWithoutAsking() {
        onPreview(saved)
        onDismiss()
    }

    Dialog(
        onDismissRequest = {
            if (dirty) askSave = true else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp)
                .background(WindowBackground.copy(alpha = 0.82f), MaterialTheme.shapes.large)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("播放显示", style = MaterialTheme.typography.titleLarge, color = OnDark)
            Text("字幕列表", style = MaterialTheme.typography.titleMedium, color = OnDark)
            SettingSwitchRow(
                title = "显示字幕列表",
                subtitle = "只对当前视频有效，下次打开会重新显示",
                checked = showSubtitleList,
                onCheckedChange = onShowSubtitleList,
            )
            SettingSwitchRow(
                title = "沉浸列表",
                subtitle = if (!immersiveAvailable) {
                    immersiveUnavailableReason ?: "当前画面方向与屏幕方向不一致，无法使用沉浸列表"
                } else {
                    "画面铺满，字幕列表半透明叠在上层"
                },
                checked = draft.immersiveList && immersiveAvailable,
                enabled = listControlsEnabled && immersiveAvailable,
                onCheckedChange = { checked ->
                    draft = draft.copy(immersiveList = checked)
                    onPreview(draft)
                },
            )
            Text(
                "列表位置",
                style = MaterialTheme.typography.titleSmall,
                color = if (immersiveControlsEnabled) OnDark else OnDarkMuted,
            )
            if (devicePortrait) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.immersiveListSidePortrait == ImmersiveListSidePortrait.Top,
                        enabled = immersiveControlsEnabled,
                        onClick = {
                            draft = draft.copy(immersiveListSidePortrait = ImmersiveListSidePortrait.Top)
                            onPreview(draft)
                        },
                        label = { Text("上部") },
                    )
                    FilterChip(
                        selected = draft.immersiveListSidePortrait == ImmersiveListSidePortrait.Bottom,
                        enabled = immersiveControlsEnabled,
                        onClick = {
                            draft = draft.copy(immersiveListSidePortrait = ImmersiveListSidePortrait.Bottom)
                            onPreview(draft)
                        },
                        label = { Text("下部") },
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.immersiveListSideLandscape == ImmersiveListSideLandscape.Left,
                        enabled = immersiveControlsEnabled,
                        onClick = {
                            draft = draft.copy(immersiveListSideLandscape = ImmersiveListSideLandscape.Left)
                            onPreview(draft)
                        },
                        label = { Text("左侧") },
                    )
                    FilterChip(
                        selected = draft.immersiveListSideLandscape == ImmersiveListSideLandscape.Right,
                        enabled = immersiveControlsEnabled,
                        onClick = {
                            draft = draft.copy(immersiveListSideLandscape = ImmersiveListSideLandscape.Right)
                            onPreview(draft)
                        },
                        label = { Text("右侧") },
                    )
                }
            }
            SettingSliderRow(
                title = "列表背景透明度",
                valueLabel = "${(draft.immersiveListOpacity * 100).toInt()}%",
                value = draft.immersiveListOpacity,
                valueRange = 0f..1f,
                enabled = immersiveControlsEnabled,
                onValueChange = {
                    draft = draft.copy(immersiveListOpacity = it)
                    onPreview(draft)
                },
            )
            SettingSliderRow(
                title = if (devicePortrait) "列表高度" else "列表宽度",
                valueLabel = "${draft.immersiveListSizePercent}%",
                value = draft.immersiveListSizePercent.toFloat(),
                valueRange = 18f..70f,
                enabled = immersiveControlsEnabled,
                onValueChange = {
                    draft = draft.copy(immersiveListSizePercent = it.toInt())
                    onPreview(draft)
                },
            )
            Text(
                "在画面边缘拖动列表，可以调整宽度或高度。",
                color = if (immersiveControlsEnabled) OnDark else OnDarkMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            Text("画面字幕", style = MaterialTheme.typography.titleMedium, color = OnDark)
            SettingSwitchRow(
                title = "显示画面字幕",
                checked = draft.onscreenEnabled,
                onCheckedChange = {
                    draft = draft.copy(onscreenEnabled = it)
                    onPreview(draft)
                },
            )
            SettingSliderRow(
                title = "字体大小",
                valueLabel = "${draft.onscreenFontSize}",
                value = draft.onscreenFontSize.toFloat(),
                valueRange = 12f..72f,
                enabled = captionControlsEnabled,
                onValueChange = {
                    draft = draft.copy(onscreenFontSize = it.toInt())
                    onPreview(draft)
                },
            )
            Text(
                "文字颜色",
                style = MaterialTheme.typography.titleSmall,
                color = if (captionControlsEnabled) OnDark else OnDarkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PRESET_COLORS.forEach { hex ->
                    val selected = draft.onscreenColor.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(parseColorArgb(hex)))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) AccentPurple else Color.White.copy(alpha = 0.25f),
                                shape = CircleShape,
                            )
                            .clickable(enabled = captionControlsEnabled) {
                                draft = draft.copy(onscreenColor = hex)
                                onPreview(draft)
                            },
                    )
                }
            }
            SettingSliderRow(
                title = "底条透明度",
                valueLabel = "${(draft.onscreenBgOpacity * 100).toInt()}%",
                value = draft.onscreenBgOpacity,
                valueRange = 0f..1f,
                enabled = captionControlsEnabled,
                onValueChange = {
                    draft = draft.copy(onscreenBgOpacity = it)
                    onPreview(draft)
                },
            )
            SettingSliderRow(
                title = "画面字幕宽度",
                valueLabel = "${draft.onscreenWidthPercent}%",
                value = draft.onscreenWidthPercent.toFloat(),
                valueRange = 30f..100f,
                enabled = captionControlsEnabled,
                onValueChange = {
                    draft = draft.copy(onscreenWidthPercent = it.toInt())
                    onPreview(draft)
                },
            )
            Text(
                "画面字幕位置",
                style = MaterialTheme.typography.titleSmall,
                color = if (captionControlsEnabled) OnDark else OnDarkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    OnScreenSubtitlePosition.Top to "上方",
                    OnScreenSubtitlePosition.Middle to "中间",
                    OnScreenSubtitlePosition.Bottom to "下方",
                ).forEach { (pos, label) ->
                    FilterChip(
                        selected = draft.onscreenPosition == pos,
                        enabled = captionControlsEnabled,
                        onClick = {
                            draft = draft.copy(onscreenPosition = pos)
                            onPreview(draft)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { closeWithoutAsking() }) { Text("取消") }
                TextButton(onClick = { onConfirm(draft) }) { Text("确定") }
            }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("保存显示设置？") },
            text = { Text("画面字幕和沉浸列表有改动。确认会记住这些设置；放弃则恢复打开前的样子。") },
            confirmButton = {
                TextButton(onClick = {
                    askSave = false
                    onConfirm(draft)
                }) { Text("确认修改") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askSave = false
                    closeWithoutAsking()
                }) { Text("放弃修改") }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) OnDark else OnDarkMuted)
            if (subtitle != null) {
                Text(subtitle, color = OnDarkMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) OnDark else OnDarkMuted)
            Text(valueLabel, color = if (enabled) AccentPurple else OnDarkMuted, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
        )
    }
}
