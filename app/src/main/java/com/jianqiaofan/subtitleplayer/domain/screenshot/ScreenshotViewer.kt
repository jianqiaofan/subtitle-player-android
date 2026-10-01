package com.jianqiaofan.subtitleplayer.domain.screenshot

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

const val TIP_ID_SCREENSHOT_EXPORT = "screenshot_export"
const val VIEWER_MODE_HINT =
    "已退出播放模式，进入看图模式。\n点击右上角关闭图标可退出看图模式，返回播放模式。"
const val VIEWER_CONTRAST_THRESHOLD = 0.55
const val MANAGE_RECENT_FOLDERS_MAX = 15

private val MANAGE_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 「截屏保存」默认文件名：`zm-标题.jpg`；标题空时用 `zm-截图.jpg`。 */
fun screenshotExportFileName(title: String): String {
    val label = sanitizeExportPart(title.trim()).ifBlank { "截图" }
    val stem = "zm-$label".trimEnd('.', ' ').ifBlank { "zm-截图" }
    return "${stem.take(180).trimEnd('.', ' ')}.jpg"
}

/** Kept for call sites that still pass a video stem; export name no longer includes it. */
fun plainScreenshotFileName(videoStem: String, title: String): String =
    screenshotExportFileName(title)

fun sortedScreenshots(shots: List<ScreenshotShot>): List<ScreenshotShot> =
    shots.sortedWith(compareBy({ timeMillis(it.time) }, { it.createdAt }, { it.id }))

/** Index into [sortedScreenshots] of the shot closest to [seconds]. -1 when empty. */
fun nearestScreenshotIndex(shots: List<ScreenshotShot>, seconds: Double): Int {
    val ordered = sortedScreenshots(shots)
    if (ordered.isEmpty()) return -1
    val target = timeMillis(seconds)
    var best = 0
    var bestGap = abs(timeMillis(ordered[0].time) - target)
    for (index in 1 until ordered.size) {
        val gap = abs(timeMillis(ordered[index].time) - target)
        if (gap < bestGap) {
            best = index
            bestGap = gap
        }
    }
    return best
}

fun screenshotDisplayTitle(shot: ScreenshotShot): String =
    shot.title.trim().ifBlank { "截图" }

fun formatScreenshotStamp(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return MANAGE_TIME_FMT.withZone(zone).format(Instant.ofEpochMilli(millis))
}

fun resolveScreenshotStamp(jsonMillis: Long, fileMillis: Long): Long = when {
    jsonMillis > 0L -> jsonMillis
    fileMillis > 0L -> fileMillis
    else -> 0L
}

/**
 * Notes ordered by creation time. Multiple notes get `Note 1`…; a single note has no number title.
 */
fun noteNumberLabel(notes: List<ScreenshotNote>, noteId: String): String? {
    if (notes.size <= 1) return null
    val ordered = notes.sortedWith(compareBy({ it.createdAt }, { it.id }))
    val index = ordered.indexOfFirst { it.id == noteId }
    if (index < 0) return null
    return "Note ${index + 1}"
}

fun otherNumberedNotes(notes: List<ScreenshotNote>, exceptId: String): List<Pair<String, ScreenshotNote>> {
    if (notes.size <= 1) return emptyList()
    return notes
        .filter { it.id != exceptId }
        .mapNotNull { note -> noteNumberLabel(notes, note.id)?.let { it to note } }
}

/**
 * Copy size + style from [source] onto [target], keeping [target]'s position (x/y).
 */
fun reuseNoteStyle(target: ScreenshotNote, source: ScreenshotNote, nowMs: Long): ScreenshotNote {
    val from = displayBox(source)
    val at = displayBox(target)
    val box = from.copy(
        x = at.x.coerceIn(0.0, (1.0 - from.width).coerceAtLeast(0.0)),
        y = at.y.coerceIn(0.0, (1.0 - from.height).coerceAtLeast(0.0)),
        width = from.width.coerceIn(NOTE_MIN_WIDTH, 1.0),
        height = from.height.coerceIn(NOTE_MIN_HEIGHT, 1.0),
        background = normalizeColor(from.background, NOTE_COLOR_WHITE),
        opacity = normalizeOpacity(from.opacity),
        font = normalizeFont(from.font),
        color = normalizeColor(from.color, NOTE_COLOR_BLACK),
        align = normalizeAlign(from.align),
    )
    return target.copy(box = box, updatedAt = nowMs)
}

fun touchShotUpdated(shot: ScreenshotShot, notes: List<ScreenshotNote>, nowMs: Long): ScreenshotShot =
    shot.copy(notes = notes, updatedAt = nowMs)

/** Relative luminance 0…1 from sRGB bytes; threshold matches desktop (~0.55). */
fun averageLuminance01(r: Int, g: Int, b: Int): Double =
    (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

fun viewerControlsOnLight(luminance01: Double, threshold: Double = VIEWER_CONTRAST_THRESHOLD): Boolean =
    luminance01 >= threshold

enum class ManagePagingMode { All, ByPath }

data class ManagedPathGroup(
    val relativeDir: String,
    val items: List<Int>,
)

/** Group row indices by relative directory of the video (empty string = selected root). */
fun groupManagedIndicesByPath(relativeDirs: List<String>): List<ManagedPathGroup> {
    val buckets = linkedMapOf<String, MutableList<Int>>()
    relativeDirs.forEachIndexed { index, dir ->
        val key = dir.trim('/').trim()
        buckets.getOrPut(key) { mutableListOf() }.add(index)
    }
    return buckets.map { (dir, indices) -> ManagedPathGroup(dir, indices) }
}

fun managePageCount(mode: ManagePagingMode, pathGroups: List<ManagedPathGroup>): Int = when (mode) {
    ManagePagingMode.All -> 1
    ManagePagingMode.ByPath -> pathGroups.size.coerceAtLeast(1)
}

fun managePageIndices(
    mode: ManagePagingMode,
    pathGroups: List<ManagedPathGroup>,
    totalCount: Int,
    pageIndex: Int,
): List<Int> = when (mode) {
    ManagePagingMode.All -> (0 until totalCount).toList()
    ManagePagingMode.ByPath -> {
        if (pathGroups.isEmpty()) emptyList()
        else pathGroups[pageIndex.coerceIn(0, pathGroups.lastIndex)].items
    }
}

/** Relative path for list column: `子目录/视频名.mp4.data/screenshot/id.png` style display. */
fun managedRelativePath(relativeDir: String, mediaFileName: String, imageName: String): String {
    val folder = if (relativeDir.isBlank()) "" else "$relativeDir/"
    return "$folder$mediaFileName.data/screenshot/$imageName"
}

private fun sanitizeExportPart(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|\r\n]"""), " ").trim()
