package com.jianqiaofan.subtitleplayer.domain.update

import com.jianqiaofan.subtitleplayer.domain.cloud.isSha256Hex
import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

const val LATEST_RELEASE_URL = "https://subtitle.gcsfg.work/releases/latest.json"

const val MSG_UP_TO_DATE = "已是最新版本"
const val MSG_DAMAGED = "更新包损坏"
const val MSG_BAD_URL = "更新地址无效，已取消下载。"
const val MSG_BAD_MANIFEST = "版本说明无法识别。"
const val MSG_OFFLINE = "无法连接服务器。"
const val MSG_NEED_INSTALL_PERMISSION = "请允许本应用安装未知应用，然后再次点「安装更新」。"

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val notes: String,
)

fun isNewerRelease(localVersionCode: Int, remoteVersionCode: Int): Boolean =
    remoteVersionCode > localVersionCode

/** Auto-prompt at most once per remote versionCode (after user has already been offered it). */
fun shouldAutoPromptUpdate(remoteVersionCode: Int, lastPromptedVersionCode: Int): Boolean =
    remoteVersionCode > lastPromptedVersionCode

fun releaseUrlAllowed(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    if (!uri.isAbsolute || uri.scheme != "https") return false
    if (uri.rawAuthority != uri.host || uri.host != "subtitle.gcsfg.work") return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    val path = uri.path ?: return false
    if (!path.startsWith("/releases/")) return false
    if (path.contains('\\') || path.split('/').any { it == ".." || it == "." }) return false
    return path.removePrefix("/releases/").isNotBlank()
}

fun parseAppRelease(body: String): AppRelease? {
    val root = try {
        parseJson(body) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return null
    val versionCode = (root.map["versionCode"] as? JsonValue.Num)?.longOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        ?: return null
    val versionName = root.map.str("versionName")?.takeIf { it.isNotBlank() } ?: return null
    val url = root.map.str("url")?.takeIf { it.isNotBlank() } ?: return null
    val sha256 = root.map.str("sha256").orEmpty()
    if (sha256.isNotEmpty() && !isSha256Hex(sha256)) return null
    val notes = root.map.str("notes").orEmpty()
    return AppRelease(versionCode, versionName, url, sha256, notes)
}

sealed class ReleaseDecision {
    data object UpToDate : ReleaseDecision()
    data class Download(val release: AppRelease) : ReleaseDecision()
    data class Invalid(val message: String) : ReleaseDecision()
}

fun decideRelease(localVersionCode: Int, release: AppRelease): ReleaseDecision {
    if (!isNewerRelease(localVersionCode, release.versionCode)) return ReleaseDecision.UpToDate
    if (!isSha256Hex(release.sha256)) return ReleaseDecision.Invalid(MSG_BAD_MANIFEST)
    if (!releaseUrlAllowed(release.url)) return ReleaseDecision.Invalid(MSG_BAD_URL)
    return ReleaseDecision.Download(release)
}

fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
