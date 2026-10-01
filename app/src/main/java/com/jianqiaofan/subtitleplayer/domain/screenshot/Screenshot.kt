package com.jianqiaofan.subtitleplayer.domain.screenshot

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.arr
import com.jianqiaofan.subtitleplayer.domain.json.jsonNumber
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import com.jianqiaofan.subtitleplayer.domain.model.SubtitleCue
import com.jianqiaofan.subtitleplayer.domain.subtitle.findCueIndexAtTime
import com.jianqiaofan.subtitleplayer.domain.subtitle.formatClock
import com.jianqiaofan.subtitleplayer.domain.tags.SubtitleListRow
import java.security.SecureRandom
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val SCREENSHOT_DIR = "screenshot"
const val SCREENSHOT_MANIFEST = "screenshots.json"
const val SCREENSHOT_VERSION = 1
const val MAX_SCREENSHOTS = 500
const val MAX_NOTES_PER_SHOT = 100
const val MAX_NOTE_JSON_BYTES = 200_000
const val MAX_TITLE_CHARS = 4_000
const val MAX_WEBP_BYTES = 2 * 1024 * 1024
const val WEBP_MAX_EDGE = 1_280
const val WEBP_QUALITY = 40
const val FONT_PIXEL_REFERENCE = 480.0
const val FONT_MIN = 0.02
const val FONT_MAX = 0.16
const val OPACITY_MIN = 0.15
const val OPACITY_MAX = 1.0

const val NOTE_COLOR_BLACK = "#1A1A1A"
const val NOTE_COLOR_WHITE = "#FFFFFF"
const val NOTE_COLOR_YELLOW = "#FFD54A"
const val NOTE_COLOR_RED = "#E53935"
const val NOTE_COLOR_BLUE = "#1565C0"
const val NOTE_COLOR_GREEN = "#2E7D32"
const val NOTE_COLOR_PURPLE = "#7A4EB5"

val NOTE_TEXT_COLORS = listOf(
    NOTE_COLOR_BLACK,
    NOTE_COLOR_WHITE,
    NOTE_COLOR_YELLOW,
    NOTE_COLOR_RED,
    NOTE_COLOR_BLUE,
    NOTE_COLOR_GREEN,
    NOTE_COLOR_PURPLE,
)

val NOTE_BACKGROUND_COLORS = listOf(
    NOTE_COLOR_WHITE,
    NOTE_COLOR_BLACK,
    NOTE_COLOR_YELLOW,
    NOTE_COLOR_RED,
    NOTE_COLOR_BLUE,
    NOTE_COLOR_GREEN,
    NOTE_COLOR_PURPLE,
)

private val ID_PATTERN = Regex("^[0-9a-f]{12}$")
private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

data class NoteBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val background: String = NOTE_COLOR_WHITE,
    val opacity: Double = 0.85,
    val font: Double = 0.06,
    val color: String = NOTE_COLOR_BLACK,
    val align: String = "center",
)

data class ScreenshotNote(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val box: NoteBox? = null,
)

data class ScreenshotShot(
    val id: String,
    val title: String,
    val time: Double,
    val frame: Long?,
    val image: String,
    val createdAt: Long,
    val updatedAt: Long,
    val notes: List<ScreenshotNote> = emptyList(),
)

data class ScreenshotDocument(
    val version: Int = SCREENSHOT_VERSION,
    val screenshots: List<ScreenshotShot> = emptyList(),
)

data class RemoteScreenshot(
    val id: String,
    val title: String,
    val time: Double,
    val frame: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val notes: List<ScreenshotNote>,
    val hasImage: Boolean,
    val imageHash: String,
)

enum class ShotImageWork {
    None,
    Upload,
    Extract,
    Download,
}

sealed class PlaybackRow {
    data class Cue(val cueIndex: Int) : PlaybackRow()
    data class Shot(val id: String) : PlaybackRow()
    data class Unmatched(val entryId: String) : PlaybackRow()
}

data class FittedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

fun isScreenshotId(value: String): Boolean = ID_PATTERN.matches(value)

fun newScreenshotId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(6)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

const val NOTE_MIN_WIDTH = 0.04
const val NOTE_MIN_HEIGHT = 0.03
const val NOTE_PANEL_WIDTH_DP = 208
const val NOTE_TITLE_MAX_CHARS = 26
const val NOTE_EDGE_HIT_PX = 40f
const val NOTE_HANDLE_SIZE_DP = 28
const val NOTE_HANDLE_VISUAL_DP = 14
const val NOTE_CUE_PICK_BEFORE = 200
const val NOTE_CUE_PICK_AFTER = 200
const val NOTE_CHIP_LABEL_CHARS = 12
const val NOTE_ACTIVE_BORDER_HEX = "#E2C6FF"

data class NearbyCueWindow(
    val cues: List<SubtitleCue>,
    /** Index into [cues] for the nearest non-empty cue; null when the window is empty. */
    val centerInWindow: Int?,
)

fun defaultNoteBox(index: Int = 0, count: Int = 1): NoteBox {
    if (count <= 1) {
        return NoteBox(x = 0.25, y = 0.35, width = 0.5, height = 0.3)
    }
    val width = min(0.42, 0.9 / count.coerceAtLeast(1))
    val gap = ((1.0 - width * count) / (count + 1)).coerceAtLeast(0.02)
    val x = (gap + index * (width + gap)).coerceIn(0.0, 1.0 - width)
    return NoteBox(x = x, y = 0.35, width = width, height = 0.28)
}

fun displayBox(note: ScreenshotNote, index: Int = 0, count: Int = 1): NoteBox =
    note.box ?: defaultNoteBox(index, count)

fun notePanelTitle(text: String): String {
    val trimmed = text.trim().replace('\n', ' ')
    if (trimmed.isEmpty()) return "这条笔记"
    return if (trimmed.length <= NOTE_TITLE_MAX_CHARS) trimmed else trimmed.take(NOTE_TITLE_MAX_CHARS - 1) + "…"
}

/** Body text with newlines removed, then the first [maxChars] characters. */
fun noteTagLabel(text: String, maxChars: Int = NOTE_CHIP_LABEL_CHARS): String {
    val flat = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\n", "")
        .trim()
    if (flat.isEmpty()) return "空笔记"
    return if (flat.length <= maxChars) flat else flat.take(maxChars)
}

/** 「创建 …」 when untouched; 「修改 …」 after an edit. Uses the device timezone. */
fun noteChipTimeLabel(
    note: ScreenshotNote,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val edited = note.updatedAt > note.createdAt
    val millis = if (edited) note.updatedAt else note.createdAt
    val stamp = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(zone)
        .format(java.time.Instant.ofEpochMilli(millis))
    return if (edited) "修改 $stamp" else "创建 $stamp"
}

/**
 * Join selected cue texts into one line for the note content box.
 * Pieces are separated by commas; the last piece ends with a period.
 * All-English content uses half-width `,` / `.`; otherwise full-width `，` / `。`.
 */
fun joinCueTextsForNote(texts: List<String>): String {
    val pieces = texts.map { flattenCuePiece(it) }.filter { it.isNotEmpty() }
    if (pieces.isEmpty()) return ""
    val english = isAllEnglishContent(pieces.joinToString(" "))
    val comma = if (english) "," else "，"
    val period = if (english) "." else "。"
    val normalized = pieces.map { piece ->
        if (english) {
            piece.replace('，', ',').replace('。', '.')
        } else {
            piece.replace(',', '，').replace('.', '。')
        }
    }
    return normalized.joinToString(comma) + period
}

/** Append a newly confirmed cue batch on its own line. */
fun appendJoinedCueTexts(current: String, joined: String): String {
    val piece = joined.trim()
    if (piece.isEmpty()) return current
    if (current.isBlank()) return piece
    return current.trimEnd() + "\n" + piece
}

private fun flattenCuePiece(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trim()

internal fun isAllEnglishContent(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) {
        return text.none { ch -> ch.code > 0x7F && !ch.isWhitespace() }
    }
    return letters.all { it in 'A'..'Z' || it in 'a'..'z' }
}

/**
 * Suggested title for a new screenshot: `文件名` or `文件名-标签…`
 * in tag-picker order. Does not use the cue text.
 */
fun suggestedScreenshotTitle(videoStem: String, cueTags: List<String>): String {
    val stem = videoStem.trim()
    val tags = com.jianqiaofan.subtitleplayer.domain.tags.orderedTagNames(cueTags)
    if (tags.isEmpty()) return stem
    return if (stem.isEmpty()) tags.joinToString("-") else (listOf(stem) + tags).joinToString("-")
}

/** Append selected tags after the current title with `-`. Empty selection leaves [title] unchanged. */
fun appendScreenshotTitleTags(title: String, tags: List<String>): String {
    val ordered = com.jianqiaofan.subtitleplayer.domain.tags.orderedTagNames(tags)
    if (ordered.isEmpty()) return title
    val base = title.trimEnd()
    return if (base.isEmpty()) ordered.joinToString("-") else "$base-${ordered.joinToString("-")}"
}

/**
 * True when any hyphen-separated segment of [title] matches a known tag
 * (presets plus custom tags already used on this video).
 */
fun titleHasKnownTag(title: String, knownTags: Collection<String>): Boolean {
    val known = knownTags.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    if (known.isEmpty()) return false
    return title.split('-').any { part -> part.trim() in known }
}

/**
 * Non-empty cues around the playhead: up to [before] before the nearest cue
 * and [after] after it, including the center cue itself.
 */
fun nearbyCuesAroundTime(
    cues: List<SubtitleCue>,
    timeSec: Double,
    before: Int = NOTE_CUE_PICK_BEFORE,
    after: Int = NOTE_CUE_PICK_AFTER,
): NearbyCueWindow {
    val nonempty = cues.filter { it.text.trim().isNotEmpty() }
    if (nonempty.isEmpty()) return NearbyCueWindow(emptyList(), null)
    val center = findCueIndexAtTime(nonempty, timeSec).coerceIn(0, nonempty.lastIndex)
    val from = (center - before).coerceAtLeast(0)
    val to = (center + after).coerceAtMost(nonempty.lastIndex)
    return NearbyCueWindow(
        cues = nonempty.subList(from, to + 1),
        centerInWindow = center - from,
    )
}

fun isLightBackground(hex: String): Boolean {
    val text = hex.trim().removePrefix("#")
    if (text.length != 6) return true
    val value = text.toIntOrNull(16) ?: return true
    val r = (value shr 16) and 0xFF
    val g = (value shr 8) and 0xFF
    val b = value and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) >= 140
}

fun moveNoteBox(box: NoteBox, dx: Double, dy: Double): NoteBox {
    val width = box.width.coerceIn(NOTE_MIN_WIDTH, 1.0)
    val height = box.height.coerceIn(NOTE_MIN_HEIGHT, 1.0)
    return box.copy(
        x = (box.x + dx).coerceIn(0.0, 1.0 - width),
        y = (box.y + dy).coerceIn(0.0, 1.0 - height),
        width = width,
        height = height,
    )
}

fun resizeNoteBox(box: NoteBox, dw: Double, dh: Double, fromLeft: Boolean = false, fromTop: Boolean = false): NoteBox {
    var x = box.x
    var y = box.y
    var width = box.width
    var height = box.height
    if (fromLeft) {
        val right = (x + width).coerceIn(NOTE_MIN_WIDTH, 1.0)
        x = (x + dw).coerceIn(0.0, right - NOTE_MIN_WIDTH)
        width = (right - x).coerceIn(NOTE_MIN_WIDTH, 1.0)
    } else {
        width = (width + dw).coerceIn(NOTE_MIN_WIDTH, (1.0 - x).coerceAtLeast(NOTE_MIN_WIDTH))
    }
    if (fromTop) {
        val bottom = (y + height).coerceIn(NOTE_MIN_HEIGHT, 1.0)
        y = (y + dh).coerceIn(0.0, bottom - NOTE_MIN_HEIGHT)
        height = (bottom - y).coerceIn(NOTE_MIN_HEIGHT, 1.0)
    } else {
        height = (height + dh).coerceIn(NOTE_MIN_HEIGHT, (1.0 - y).coerceAtLeast(NOTE_MIN_HEIGHT))
    }
    // Keep the box fully inside the picture after clamping.
    if (x + width > 1.0) {
        width = (1.0 - x).coerceAtLeast(NOTE_MIN_WIDTH)
    }
    if (y + height > 1.0) {
        height = (1.0 - y).coerceAtLeast(NOTE_MIN_HEIGHT)
    }
    return box.copy(x = x, y = y, width = width, height = height)
}

/**
 * Prefer below the note, then above, right, left.
 * Stay inside the image bounds and avoid covering the text box when possible.
 */
fun placeStylePanel(
    anchorLeft: Float,
    anchorTop: Float,
    anchorWidth: Float,
    anchorHeight: Float,
    panelWidth: Float,
    panelHeight: Float,
    boundsLeft: Float,
    boundsTop: Float,
    boundsWidth: Float,
    boundsHeight: Float,
    gap: Float = 8f,
): FittedRect {
    val boundsRight = boundsLeft + boundsWidth
    val boundsBottom = boundsTop + boundsHeight
    val candidates = listOf(
        FittedRect(anchorLeft + anchorWidth / 2f - panelWidth / 2f, anchorTop + anchorHeight + gap, panelWidth, panelHeight),
        FittedRect(anchorLeft + anchorWidth / 2f - panelWidth / 2f, anchorTop - gap - panelHeight, panelWidth, panelHeight),
        FittedRect(anchorLeft + anchorWidth + gap, anchorTop + anchorHeight / 2f - panelHeight / 2f, panelWidth, panelHeight),
        FittedRect(anchorLeft - gap - panelWidth, anchorTop + anchorHeight / 2f - panelHeight / 2f, panelWidth, panelHeight),
    )
    fun outside(rect: FittedRect): Float {
        val right = rect.left + rect.width
        val bottom = rect.top + rect.height
        return max(0f, boundsLeft - rect.left) +
            max(0f, boundsTop - rect.top) +
            max(0f, right - boundsRight) +
            max(0f, bottom - boundsBottom)
    }
    fun overlap(rect: FittedRect): Float {
        val left = max(rect.left, anchorLeft)
        val top = max(rect.top, anchorTop)
        val right = min(rect.left + rect.width, anchorLeft + anchorWidth)
        val bottom = min(rect.top + rect.height, anchorTop + anchorHeight)
        return max(0f, right - left) * max(0f, bottom - top)
    }
    val best = candidates.minWith(compareBy({ if (outside(it) == 0f) 0 else 1 }, { overlap(it) }, { outside(it) }))
    return shiftInside(best, boundsLeft, boundsTop, boundsWidth, boundsHeight)
}

fun shiftInside(rect: FittedRect, boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float): FittedRect {
    var width = min(rect.width, boundsWidth)
    var height = min(rect.height, boundsHeight)
    var left = rect.left.coerceIn(boundsLeft, boundsLeft + boundsWidth - width)
    var top = rect.top.coerceIn(boundsTop, boundsTop + boundsHeight - height)
    return FittedRect(left, top, width, height)
}

enum class NoteResizeEdge { Move, Left, Top, Right, Bottom, TopLeft, TopRight, BottomLeft, BottomRight }

fun hitNoteEdge(localX: Float, localY: Float, width: Float, height: Float, hit: Float = NOTE_EDGE_HIT_PX): NoteResizeEdge {
    val left = localX <= hit
    val right = localX >= width - hit
    val top = localY <= hit
    val bottom = localY >= height - hit
    return when {
        top && left -> NoteResizeEdge.TopLeft
        top && right -> NoteResizeEdge.TopRight
        bottom && left -> NoteResizeEdge.BottomLeft
        bottom && right -> NoteResizeEdge.BottomRight
        left -> NoteResizeEdge.Left
        right -> NoteResizeEdge.Right
        top -> NoteResizeEdge.Top
        bottom -> NoteResizeEdge.Bottom
        else -> NoteResizeEdge.Move
    }
}

fun normalizeFont(raw: Double): Double {
    val ratio = if (raw > 1.0) raw / FONT_PIXEL_REFERENCE else raw
    return ratio.coerceIn(FONT_MIN, FONT_MAX)
}

fun normalizeOpacity(raw: Double): Double = raw.coerceIn(OPACITY_MIN, OPACITY_MAX)

fun normalizeAlign(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "left", "center", "right" -> raw.trim().lowercase()
    else -> "center"
}

fun normalizeColor(raw: String?, fallback: String): String {
    val text = raw?.trim().orEmpty()
    return if (HEX_COLOR.matches(text)) text.uppercase() else fallback
}

fun frameIndexAt(timeSec: Double, frameRate: Float?): Long? {
    val fps = frameRate ?: return null
    if (!fps.isFinite() || fps <= 0f || !timeSec.isFinite() || timeSec < 0.0) return null
    return (timeSec * fps).roundToLong()
}

fun fittedEdge(width: Int, height: Int, maxEdge: Int = WEBP_MAX_EDGE): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

fun fittedImageRect(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float): FittedRect {
    if (containerWidth <= 0f || containerHeight <= 0f) return FittedRect(0f, 0f, 0f, 0f)
    if (imageWidth <= 0f || imageHeight <= 0f) return FittedRect(0f, 0f, containerWidth, containerHeight)
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedRect((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}

fun screenshotListLabel(shot: ScreenshotShot): String {
    val title = shot.title.trim().ifBlank { "（无标题）" }
    return "有截图  [${formatClock(shot.time)}] $title"
}

fun timeMillis(seconds: Double): Long = round(seconds * 1000.0).toLong()

/**
 * Subtitle rows stay in their filtered order. A screenshot is placed by its time.
 * At the same millisecond every subtitle row comes before the screenshots.
 */
fun mergeScreenshotRows(
    rows: List<SubtitleListRow>,
    cues: List<SubtitleCue>,
    shots: List<ScreenshotShot>,
): List<PlaybackRow> {
    if (rows.isNotEmpty() && rows.all { it is SubtitleListRow.Unmatched }) {
        return rows.map { PlaybackRow.Unmatched((it as SubtitleListRow.Unmatched).entryId) }
    }
    val cueRows = rows.filterIsInstance<SubtitleListRow.Cue>()
    val orderedShots = shots.sortedWith(compareBy({ timeMillis(it.time) }, { it.id }))
    val merged = mutableListOf<PlaybackRow>()
    var shotIndex = 0
    for (position in cueRows.indices) {
        val cueMs = cues.getOrNull(cueRows[position].cueIndex)?.let { timeMillis(it.start) } ?: Long.MAX_VALUE
        while (shotIndex < orderedShots.size && timeMillis(orderedShots[shotIndex].time) < cueMs) {
            merged += PlaybackRow.Shot(orderedShots[shotIndex].id)
            shotIndex += 1
        }
        merged += PlaybackRow.Cue(cueRows[position].cueIndex)
        val nextMs = cueRows.getOrNull(position + 1)?.let { row ->
            cues.getOrNull(row.cueIndex)?.let { timeMillis(it.start) }
        }
        if (nextMs != cueMs) {
            while (shotIndex < orderedShots.size && timeMillis(orderedShots[shotIndex].time) == cueMs) {
                merged += PlaybackRow.Shot(orderedShots[shotIndex].id)
                shotIndex += 1
            }
        }
    }
    while (shotIndex < orderedShots.size) {
        merged += PlaybackRow.Shot(orderedShots[shotIndex].id)
        shotIndex += 1
    }
    return merged
}

fun baselineIdsForSync(manifestPresent: Boolean, wiped: Boolean, seenIds: List<String>): List<String> =
    if (manifestPresent || wiped) seenIds else emptyList()

fun planShotImage(hasPng: Boolean, frame: Long?, hasImage: Boolean): ShotImageWork = when {
    hasPng && !hasImage -> ShotImageWork.Upload
    hasPng -> ShotImageWork.None
    frame != null -> ShotImageWork.Extract
    hasImage -> ShotImageWork.Download
    else -> ShotImageWork.None
}

fun imageNameFor(id: String, png: Boolean): String = if (png) "$id.png" else "$id.webp"

fun screenshotSyncRejection(shots: List<ScreenshotShot>): String? {
    if (shots.size > MAX_SCREENSHOTS) return "一次最多 500 张"
    for (shot in shots) {
        if (!isScreenshotId(shot.id)) return "截图编号无效"
        if (shot.title.length > MAX_TITLE_CHARS) return "截图说明无效"
        if (shot.notes.size > MAX_NOTES_PER_SHOT) return "一张最多 100 条笔记"
        if (shot.notes.any { !isScreenshotId(it.id) }) return "截图说明无效"
        if (encodeNotes(shot.notes).toByteArray(Charsets.UTF_8).size > MAX_NOTE_JSON_BYTES) return "截图笔记过长"
    }
    return null
}

fun mergeScreenshots(
    sentIds: Set<String>,
    localNow: List<ScreenshotShot>,
    remote: List<RemoteScreenshot>,
): List<ScreenshotShot> {
    val localById = localNow.associateBy { it.id }
    val used = mutableSetOf<String>()
    val merged = mutableListOf<ScreenshotShot>()
    for (item in remote) {
        val local = localById[item.id]
        if (local == null) {
            if (item.id in sentIds) continue
            used += item.id
            merged += ScreenshotShot(
                id = item.id,
                title = item.title,
                time = item.time,
                frame = item.frame,
                image = "",
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                notes = item.notes,
            )
        } else {
            used += item.id
            merged += if (local.updatedAt > item.updatedAt) {
                local
            } else {
                local.copy(
                    title = item.title,
                    time = item.time,
                    frame = item.frame,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    notes = item.notes,
                )
            }
        }
    }
    for (local in localNow) {
        if (local.id !in used) merged += local
    }
    return merged
}

fun screenshotContentEquals(left: ScreenshotShot, right: ScreenshotShot): Boolean {
    if (left.id != right.id || left.title != right.title || left.time != right.time || left.frame != right.frame) return false
    if (left.notes.size != right.notes.size) return false
    return left.notes.zip(right.notes).all { (a, b) ->
        a.id == b.id && a.text == b.text && a.box == b.box
    }
}

fun parseScreenshotDocument(text: String): ScreenshotDocument? {
    val root = try {
        parseJson(text) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    val items = root.map.arr("screenshots") ?: return null
    val shots = mutableListOf<ScreenshotShot>()
    for (item in items) {
        shots += parseShot(item as? JsonValue.Obj ?: return null) ?: return null
    }
    val version = (root.map["version"] as? JsonValue.Num)?.longFlexible()?.toInt() ?: SCREENSHOT_VERSION
    return ScreenshotDocument(version, shots)
}

fun parseRemoteScreenshots(text: String): List<RemoteScreenshot>? {
    val root = try {
        parseJson(text) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    val items = root.map.arr("shots") ?: return null
    return items.map { item ->
        val obj = item as? JsonValue.Obj ?: return null
        val shot = parseShot(obj) ?: return null
        RemoteScreenshot(
            id = shot.id,
            title = shot.title,
            time = shot.time,
            frame = shot.frame,
            createdAt = shot.createdAt,
            updatedAt = shot.updatedAt,
            notes = shot.notes,
            hasImage = obj.map["has_image"] is JsonValue.Bool && (obj.map["has_image"] as JsonValue.Bool).value,
            imageHash = obj.map.str("image_hash").orEmpty(),
        )
    }
}

fun encodeScreenshotDocument(document: ScreenshotDocument): String = buildString {
    append("{\"version\":")
    append(document.version)
    append(",\"screenshots\":")
    append(encodeShots(document.screenshots, includeImage = true))
    append('}')
}

fun encodeScreenshotPut(videoHash: String, videoStem: String, shots: List<ScreenshotShot>, baselineIds: List<String>): String =
    buildString {
        append("{\"video_hash\":${jsonString(videoHash)},")
        append("\"video_stem\":${jsonString(videoStem)},")
        append("\"shots\":")
        append(encodeShots(shots, includeImage = false))
        append(",\"baseline_ids\":")
        append(encodeIdArray(baselineIds))
        append('}')
    }

private fun encodeShots(shots: List<ScreenshotShot>, includeImage: Boolean): String = buildString {
    append('[')
    shots.forEachIndexed { index, shot ->
        if (index > 0) append(',')
        append("{\"id\":${jsonString(shot.id)},")
        append("\"title\":${jsonString(shot.title)},")
        append("\"time\":${jsonNumber(shot.time)},")
        append("\"frame\":")
        append(shot.frame?.toString() ?: "null")
        if (includeImage) append(",\"image\":${jsonString(shot.image)}")
        append(",\"created_at\":${shot.createdAt},")
        append("\"updated_at\":${shot.updatedAt},")
        append("\"notes\":")
        append(encodeNotes(shot.notes))
        append('}')
    }
    append(']')
}

internal fun encodeNotes(notes: List<ScreenshotNote>): String = buildString {
    append('[')
    notes.forEachIndexed { index, note ->
        if (index > 0) append(',')
        append("{\"id\":${jsonString(note.id)},")
        append("\"text\":${jsonString(note.text)},")
        append("\"created_at\":${note.createdAt},")
        append("\"updated_at\":${note.updatedAt}")
        val box = note.box
        if (box != null) {
            append(",\"box\":{")
            append("\"x\":${jsonNumber(box.x)},")
            append("\"y\":${jsonNumber(box.y)},")
            append("\"width\":${jsonNumber(box.width)},")
            append("\"height\":${jsonNumber(box.height)},")
            append("\"background\":${jsonString(box.background)},")
            append("\"opacity\":${jsonNumber(box.opacity)},")
            append("\"font\":${jsonNumber(box.font)},")
            append("\"color\":${jsonString(box.color)},")
            append("\"align\":${jsonString(box.align)}}")
        }
        append('}')
    }
    append(']')
}

private fun encodeIdArray(ids: List<String>): String = buildString {
    append('[')
    ids.forEachIndexed { index, id ->
        if (index > 0) append(',')
        append(jsonString(id))
    }
    append(']')
}

private fun parseShot(obj: JsonValue.Obj): ScreenshotShot? {
    val id = obj.map.str("id") ?: return null
    if (!isScreenshotId(id)) return null
    val notesValue = obj.map["notes"]
    val notes = when (notesValue) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Arr -> notesValue.items.map { parseNote(it as? JsonValue.Obj ?: return null) ?: return null }
        else -> return null
    }
    val image = obj.map.str("image").orEmpty()
    return ScreenshotShot(
        id = id,
        title = obj.map.str("title").orEmpty(),
        time = (obj.map["time"] as? JsonValue.Num)?.value ?: 0.0,
        frame = (obj.map["frame"] as? JsonValue.Num)?.longFlexible(),
        image = image,
        createdAt = (obj.map["created_at"] as? JsonValue.Num)?.longFlexible() ?: 0L,
        updatedAt = (obj.map["updated_at"] as? JsonValue.Num)?.longFlexible() ?: 0L,
        notes = notes,
    )
}

private fun parseNote(obj: JsonValue.Obj?): ScreenshotNote? {
    if (obj == null) return null
    val id = obj.map.str("id") ?: return null
    if (!isScreenshotId(id)) return null
    val boxValue = obj.map["box"]
    val box = when (boxValue) {
        null, JsonValue.Null -> null
        is JsonValue.Obj -> parseBox(boxValue.map)
        else -> return null
    }
    return ScreenshotNote(
        id = id,
        text = obj.map.str("text").orEmpty(),
        createdAt = (obj.map["created_at"] as? JsonValue.Num)?.longFlexible() ?: 0L,
        updatedAt = (obj.map["updated_at"] as? JsonValue.Num)?.longFlexible() ?: 0L,
        box = box,
    )
}

private fun parseBox(map: Map<String, JsonValue>): NoteBox {
    fun num(key: String, fallback: Double): Double = (map[key] as? JsonValue.Num)?.value ?: fallback
    return NoteBox(
        x = num("x", 0.08).coerceIn(0.0, 1.0),
        y = num("y", 0.62).coerceIn(0.0, 1.0),
        width = num("width", 0.84).coerceIn(0.0, 1.0),
        height = num("height", 0.28).coerceIn(0.0, 1.0),
        background = normalizeColor(map.str("background"), NOTE_COLOR_WHITE),
        opacity = normalizeOpacity(num("opacity", 0.85)),
        font = normalizeFont(num("font", 0.06)),
        color = normalizeColor(map.str("color"), NOTE_COLOR_BLACK),
        align = normalizeAlign(map.str("align")),
    )
}

private fun JsonValue.Num.longFlexible(): Long? {
    longOrNull()?.let { return it }
    val asDouble = raw.toDoubleOrNull() ?: return null
    if (!asDouble.isFinite()) return null
    return asDouble.toLong()
}

private fun sanitizeFilePart(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|\r\n]"""), " ").trim()
