package com.jianqiaofan.subtitleplayer.domain.time

import kotlin.math.round

data class TimeFields(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val millis: Int,
) {
    fun toSeconds(): Double = hours * 3600.0 + minutes * 60.0 + seconds + millis / 1000.0
}

enum class TimePart {
    Hour,
    Minute,
    Second,
    Milli,
}

fun secondsToTimeFields(seconds: Double): TimeFields {
    val totalMs = round(seconds * 1000.0 + 1e-9).toLong().coerceAtLeast(0L)
    return millisToTimeFields(totalMs)
}

fun millisToTimeFields(totalMs: Long): TimeFields {
    val clamped = totalMs.coerceAtLeast(0L)
    val hours = (clamped / 3_600_000L).toInt()
    val rem = clamped % 3_600_000L
    val minutes = (rem / 60_000L).toInt()
    val rem2 = rem % 60_000L
    val seconds = (rem2 / 1_000L).toInt()
    val millis = (rem2 % 1_000L).toInt()
    return TimeFields(hours, minutes, seconds, millis)
}

fun TimeFields.toMillis(): Long =
    hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis

/**
 * Apply +/- to one field with carry/borrow. Hours do not wrap: if the result
 * would leave 0..999 hours, the original value is kept.
 */
fun nudgeTime(fields: TimeFields, part: TimePart, delta: Int): TimeFields {
    val add = when (part) {
        TimePart.Hour -> delta * 3_600_000L
        TimePart.Minute -> delta * 60_000L
        TimePart.Second -> delta * 1_000L
        TimePart.Milli -> delta.toLong()
    }
    val next = fields.toMillis() + add
    if (next < 0L) return fields
    val hours = next / 3_600_000L
    if (hours !in 0..999) return fields
    return millisToTimeFields(next)
}

fun padTimeComponent(value: Int, width: Int): String = value.toString().padStart(width, '0')

data class EditValidation(
    val start: TimeFields,
    val end: TimeFields,
)

sealed class EditConfirmResult {
    data class Ok(val start: Double, val end: Double) : EditConfirmResult()
    data class Error(val message: String) : EditConfirmResult()
}

fun parseTimeComponent(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.toIntOrNull()
}

fun validateTimeFields(hours: Int, minutes: Int, seconds: Int, millis: Int): String? {
    if (hours < 0 || minutes < 0 || seconds < 0 || millis < 0) return "时间不能为负"
    if (hours > 999) return "小时必须在 0～999"
    if (minutes > 59) return "分钟必须在 0～59"
    if (seconds > 59) return "秒必须在 0～59"
    if (millis > 999) return "毫秒必须在 0～999"
    return null
}

/**
 * On confirm: if start >= end, set end = start + 1s. If still invalid, reject.
 */
fun confirmEditTimes(start: TimeFields, end: TimeFields): EditConfirmResult {
    listOf(start, end).forEach { t ->
        val err = validateTimeFields(t.hours, t.minutes, t.seconds, t.millis)
        if (err != null) return EditConfirmResult.Error(err)
    }
    var startSec = start.toSeconds()
    var endSec = end.toSeconds()
    if (startSec >= endSec) {
        endSec = startSec + 1.0
    }
    if (startSec >= endSec) {
        return EditConfirmResult.Error("起始时间必须早于终止时间")
    }
    return EditConfirmResult.Ok(startSec, endSec)
}

fun coerceEndAfterStart(start: TimeFields, end: TimeFields): TimeFields {
    return if (start.toSeconds() >= end.toSeconds()) {
        secondsToTimeFields(start.toSeconds() + 1.0)
    } else {
        end
    }
}
