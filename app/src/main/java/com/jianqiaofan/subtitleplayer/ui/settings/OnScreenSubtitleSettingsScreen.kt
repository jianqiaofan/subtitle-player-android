package com.jianqiaofan.subtitleplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSideLandscape
import com.jianqiaofan.subtitleplayer.domain.display.ImmersiveListSidePortrait
import com.jianqiaofan.subtitleplayer.domain.display.OnScreenSubtitlePosition
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.display.parseColorArgb
import com.jianqiaofan.subtitleplayer.ui.theme.AccentPurple
import com.jianqiaofan.subtitleplayer.ui.theme.OnDarkMuted
import com.jianqiaofan.subtitleplayer.ui.theme.SurfacePanel
import com.jianqiaofan.subtitleplayer.ui.theme.WindowBackground

private val PRESET_COLORS = listOf(
    "#FFFFFF", "#FFE082", "#80CBC4", "#90CAF9", "#F48FB1", "#B980FF",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnScreenSubtitleSettingsScreen(
    settings: PlayerDisplaySettings,
    immersiveAvailable: Boolean,
    immersiveUnavailableReason: String?,
    devicePortrait: Boolean,
    onChange: (PlayerDisplaySettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WindowBackground,
        topBar = {
            TopAppBar(
                title = { Text("画面字幕") },
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
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "在视频画面上显示当前字幕（半透明底条 + 实心文字）。修改后即时生效。",
                color = OnDarkMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            SettingSwitchRow(
                title = "显示画面字幕",
                checked = settings.onscreenEnabled,
                onCheckedChange = { onChange(settings.copy(onscreenEnabled = it)) },
            )

            SettingSwitchRow(
                title = "沉浸列表",
                subtitle = if (!immersiveAvailable) {
                    immersiveUnavailableReason
                        ?: "当前画面方向与屏幕方向不一致，无法使用沉浸列表"
                } else {
                    "画面铺满，字幕列表半透明叠在上层"
                },
                checked = settings.immersiveList && immersiveAvailable,
                enabled = immersiveAvailable,
                onCheckedChange = { onChange(settings.copy(immersiveList = it)) },
            )

            if (settings.immersiveList && immersiveAvailable) {
                Text("列表位置", style = MaterialTheme.typography.titleSmall)
                if (devicePortrait) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.immersiveListSidePortrait == ImmersiveListSidePortrait.Top,
                            onClick = {
                                onChange(
                                    settings.copy(
                                        immersiveListSidePortrait = ImmersiveListSidePortrait.Top,
                                    ),
                                )
                            },
                            label = { Text("上部") },
                        )
                        FilterChip(
                            selected = settings.immersiveListSidePortrait == ImmersiveListSidePortrait.Bottom,
                            onClick = {
                                onChange(
                                    settings.copy(
                                        immersiveListSidePortrait = ImmersiveListSidePortrait.Bottom,
                                    ),
                                )
                            },
                            label = { Text("下部") },
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.immersiveListSideLandscape == ImmersiveListSideLandscape.Left,
                            onClick = {
                                onChange(
                                    settings.copy(
                                        immersiveListSideLandscape = ImmersiveListSideLandscape.Left,
                                    ),
                                )
                            },
                            label = { Text("左侧") },
                        )
                        FilterChip(
                            selected = settings.immersiveListSideLandscape == ImmersiveListSideLandscape.Right,
                            onClick = {
                                onChange(
                                    settings.copy(
                                        immersiveListSideLandscape = ImmersiveListSideLandscape.Right,
                                    ),
                                )
                            },
                            label = { Text("右侧") },
                        )
                    }
                }

                SettingSliderRow(
                    title = "列表背景透明度",
                    valueLabel = "${(settings.immersiveListOpacity * 100).toInt()}%",
                    value = settings.immersiveListOpacity,
                    valueRange = 0f..1f,
                    onValueChange = { onChange(settings.copy(immersiveListOpacity = it)) },
                )

                SettingSliderRow(
                    title = if (devicePortrait) "列表高度" else "列表宽度",
                    valueLabel = "${settings.immersiveListSizePercent}%",
                    value = settings.immersiveListSizePercent.toFloat(),
                    valueRange = 18f..70f,
                    onValueChange = {
                        onChange(settings.copy(immersiveListSizePercent = it.toInt()))
                    },
                )
            }

            SettingSliderRow(
                title = "字体大小",
                valueLabel = "${settings.onscreenFontSize}",
                value = settings.onscreenFontSize.toFloat(),
                valueRange = 12f..72f,
                onValueChange = { onChange(settings.copy(onscreenFontSize = it.toInt())) },
            )

            Text("文字颜色", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PRESET_COLORS.forEach { hex ->
                    val selected = settings.onscreenColor.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(parseColorArgb(hex)))
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, AccentPurple, CircleShape)
                                } else {
                                    Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                },
                            )
                            .clickable { onChange(settings.copy(onscreenColor = hex)) },
                    )
                }
            }
            Text(settings.onscreenColor, color = OnDarkMuted, style = MaterialTheme.typography.labelMedium)

            SettingSliderRow(
                title = "底条透明度",
                valueLabel = "${(settings.onscreenBgOpacity * 100).toInt()}%",
                value = settings.onscreenBgOpacity,
                valueRange = 0f..1f,
                onValueChange = { onChange(settings.copy(onscreenBgOpacity = it)) },
            )

            SettingSliderRow(
                title = "画面字幕宽度",
                valueLabel = "${settings.onscreenWidthPercent}%",
                value = settings.onscreenWidthPercent.toFloat(),
                valueRange = 30f..100f,
                onValueChange = { onChange(settings.copy(onscreenWidthPercent = it.toInt())) },
            )

            Text("画面字幕位置", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    OnScreenSubtitlePosition.Top to "上方",
                    OnScreenSubtitlePosition.Middle to "中间",
                    OnScreenSubtitlePosition.Bottom to "下方",
                ).forEach { (pos, label) ->
                    FilterChip(
                        selected = settings.onscreenPosition == pos,
                        onClick = { onChange(settings.copy(onscreenPosition = pos)) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
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
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, color = AccentPurple, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}
