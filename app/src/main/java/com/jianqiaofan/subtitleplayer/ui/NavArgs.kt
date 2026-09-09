package com.jianqiaofan.subtitleplayer.ui

import java.util.Base64

/**
 * SAF document URIs contain `%3A` / `%2F` as part of the path.
 * Navigation already URI-decodes path arguments, so a second [android.net.Uri.decode]
 * (or putting the raw URI in the route) turns those sequences into `/` and splits the URI.
 * Base64 keeps the original bytes intact through the back stack.
 */
fun encodeNavArg(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

fun decodeNavArg(value: String): String {
    if (value.isEmpty()) return value
    return String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
