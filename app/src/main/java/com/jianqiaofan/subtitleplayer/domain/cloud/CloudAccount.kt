package com.jianqiaofan.subtitleplayer.domain.cloud

const val DEFAULT_CLOUD_SERVER = "https://subtitle.gcsfg.work"

data class UsernameCheck(val username: String, val error: String?)

fun normalizeServer(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return trimmed.ifBlank { DEFAULT_CLOUD_SERVER }
}

fun serverAddressError(raw: String): String? {
    val url = normalizeServer(raw)
    if (!url.startsWith("https://") || url.length <= "https://".length) {
        return "请使用 HTTPS 服务器地址。"
    }
    return null
}

fun validateUsername(raw: String): UsernameCheck {
    val username = raw.trim()
    if (username.any { it.isWhitespace() }) {
        return UsernameCheck(username, "用户名中间不能有空格。")
    }
    if (username.length !in 3..32) {
        return UsernameCheck(username, "用户名需要 3 到 32 个字符。")
    }
    val allowed = username.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
    if (!allowed) {
        return UsernameCheck(username, "用户名只能包含字母、数字、下划线、点和短横线。")
    }
    if (username.none { it.isLetterOrDigit() }) {
        return UsernameCheck(username, "用户名至少要有一个字母或数字。")
    }
    return UsernameCheck(username, null)
}

fun validatePassword(password: String): String? =
    if (password.length !in 8..72) "密码需要 8 到 72 个字符。" else null

data class StoredCloudAccount(
    val server: String = DEFAULT_CLOUD_SERVER,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val tokenUsername: String = "",
)
