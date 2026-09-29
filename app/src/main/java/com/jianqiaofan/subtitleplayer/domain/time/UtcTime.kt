package com.jianqiaofan.subtitleplayer.domain.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun utcMillis(text: String): Long? =
    try {
        Instant.parse(text.trim()).toEpochMilli()
    } catch (_: Exception) {
        null
    }

fun utcTimestamp(instant: Instant = Instant.now()): String =
    instant.truncatedTo(ChronoUnit.SECONDS).toString()

fun sameUtcInstant(left: String, right: String): Boolean {
    val a = utcMillis(left)
    val b = utcMillis(right)
    return if (a != null && b != null) a == b else left == right
}

fun isNewerOrEqualUtc(candidate: String, current: String): Boolean {
    val left = utcMillis(candidate)
    val right = utcMillis(current)
    return when {
        left != null && right != null -> left >= right
        left != null -> true
        right != null -> false
        else -> candidate >= current
    }
}

fun formatUtcLocal(text: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = try {
        Instant.parse(text.trim())
    } catch (_: Exception) {
        return text.trim()
    }
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(instant)
}
