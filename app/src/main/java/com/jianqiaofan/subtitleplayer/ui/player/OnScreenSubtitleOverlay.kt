package com.jianqiaofan.subtitleplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianqiaofan.subtitleplayer.domain.display.OnScreenHorizontalSpan
import com.jianqiaofan.subtitleplayer.domain.display.OnScreenSubtitlePosition
import com.jianqiaofan.subtitleplayer.domain.display.PlayerDisplaySettings
import com.jianqiaofan.subtitleplayer.domain.display.parseColorArgb

@Composable
fun OnScreenSubtitleOverlay(
    text: String?,
    settings: PlayerDisplaySettings,
    horizontalSpan: OnScreenHorizontalSpan = OnScreenHorizontalSpan.Full,
    modifier: Modifier = Modifier,
) {
    val cleaned = text?.replace("\r\n", "\n")?.trim().orEmpty()
    if (!settings.onscreenEnabled || cleaned.isEmpty()) return

    val alignment = when (settings.onscreenPosition) {
        OnScreenSubtitlePosition.Top -> Alignment.TopCenter
        OnScreenSubtitlePosition.Middle -> Alignment.Center
        OnScreenSubtitlePosition.Bottom -> Alignment.BottomCenter
    }
    val textColor = Color(parseColorArgb(settings.onscreenColor))
    val bg = Color.Black.copy(alpha = settings.onscreenBgOpacity)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val spanWidth = (horizontalSpan.endFraction - horizontalSpan.startFraction).coerceIn(0f, 1f)
        val regionWidth = maxWidth * spanWidth
        val startOffset = maxWidth * horizontalSpan.startFraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = startOffset)
                    .width(regionWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = alignment,
            ) {
                val maxBarWidth = regionWidth * (settings.onscreenWidthPercent / 100f)
                Text(
                    text = cleaned,
                    color = textColor,
                    fontSize = settings.onscreenFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = (settings.onscreenFontSize * 1.25f).sp,
                    modifier = Modifier
                        .widthIn(max = maxBarWidth)
                        .background(bg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
