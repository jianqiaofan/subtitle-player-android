package com.jianqiaofan.subtitleplayer.ui.player

data class VideoViewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal const val VIDEO_MIN_SCALE = 1f
internal const val VIDEO_MAX_SCALE = 6f

fun applyVideoViewportGesture(
    current: VideoViewport,
    zoom: Float,
    panX: Float,
    panY: Float,
    centroidX: Float,
    centroidY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    minScale: Float = VIDEO_MIN_SCALE,
    maxScale: Float = VIDEO_MAX_SCALE,
): VideoViewport {
    val newScale = (current.scale * zoom).coerceIn(minScale, maxScale)
    val ratio = if (current.scale == 0f) 1f else newScale / current.scale
    val centerX = viewportWidth / 2f
    val centerY = viewportHeight / 2f
    return clampVideoViewport(
        VideoViewport(
            scale = newScale,
            offsetX = current.offsetX * ratio + (centroidX - centerX) * (1f - ratio) + panX,
            offsetY = current.offsetY * ratio + (centroidY - centerY) * (1f - ratio) + panY,
        ),
        viewportWidth,
        viewportHeight,
        minScale,
    )
}

fun clampVideoViewport(
    current: VideoViewport,
    viewportWidth: Float,
    viewportHeight: Float,
    minScale: Float = VIDEO_MIN_SCALE,
): VideoViewport {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return current
    val scale = current.scale.coerceAtLeast(minScale)
    if (scale <= minScale + 1e-4f) {
        return VideoViewport(minScale, 0f, 0f)
    }
    val maxX = viewportWidth * (scale - 1f) / 2f
    val maxY = viewportHeight * (scale - 1f) / 2f
    return VideoViewport(
        scale = scale,
        offsetX = current.offsetX.coerceIn(-maxX, maxX),
        offsetY = current.offsetY.coerceIn(-maxY, maxY),
    )
}
