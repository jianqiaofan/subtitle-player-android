package com.jianqiaofan.subtitleplayer.data

import com.jianqiaofan.subtitleplayer.domain.cloud.AuthSession
import com.jianqiaofan.subtitleplayer.domain.cloud.CLOUD_BODY_LIMIT_BYTES
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudTagDocument
import com.jianqiaofan.subtitleplayer.domain.cloud.LibrarySubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.LibraryTag
import com.jianqiaofan.subtitleplayer.domain.cloud.SavedSubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.SavedTag
import com.jianqiaofan.subtitleplayer.domain.cloud.SharePerson
import com.jianqiaofan.subtitleplayer.domain.cloud.SyncSnapshot
import com.jianqiaofan.subtitleplayer.domain.cloud.encodeCloudTagDocument
import com.jianqiaofan.subtitleplayer.domain.cloud.parseAuthSession
import com.jianqiaofan.subtitleplayer.domain.cloud.parseErrorDetail
import com.jianqiaofan.subtitleplayer.domain.cloud.parseLibrarySubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.parseLibrarySubtitles
import com.jianqiaofan.subtitleplayer.domain.cloud.parseLibraryTag
import com.jianqiaofan.subtitleplayer.domain.cloud.parseLibraryTags
import com.jianqiaofan.subtitleplayer.domain.cloud.parseSavedSubtitle
import com.jianqiaofan.subtitleplayer.domain.cloud.parseSavedTag
import com.jianqiaofan.subtitleplayer.domain.cloud.parseShares
import com.jianqiaofan.subtitleplayer.domain.cloud.parseSyncSnapshot
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PlaybackSession
import com.jianqiaofan.subtitleplayer.domain.playbacklog.encodePlaybackUpload
import com.jianqiaofan.subtitleplayer.domain.screenshot.MAX_WEBP_BYTES
import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudException(val status: Int, message: String) : Exception(message)

class CloudApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    fun register(username: String, password: String): AuthSession =
        parseAuth(exchange("POST", "/api/auth/register", null, authBody(username, password)))

    fun login(username: String, password: String): AuthSession =
        parseAuth(exchange("POST", "/api/auth/login", null, authBody(username, password)))

    fun sync(token: String, videoHash: String): SyncSnapshot {
        val body = exchange("GET", "/api/sync?video_hash=${enc(videoHash)}", token, null)
        return parseSyncSnapshot(body) ?: throw CloudException(200, "同步响应无法识别")
    }

    fun shares(token: String, videoHash: String, username: String? = null): List<SharePerson> {
        val path = buildString {
            append("/api/shares?video_hash=")
            append(enc(videoHash))
            if (!username.isNullOrBlank()) {
                append("&username=")
                append(enc(username))
            }
        }
        val body = exchange("GET", path, token, null)
        return parseShares(body) ?: throw CloudException(200, "分享响应无法识别")
    }

    fun listSubtitles(token: String): List<LibrarySubtitle> {
        val body = exchange("GET", "/api/library/subtitles", token, null)
        return parseLibrarySubtitles(body) ?: throw CloudException(200, "字幕列表无法识别")
    }

    fun subtitle(token: String, id: Long): LibrarySubtitle {
        val body = exchange("GET", "/api/library/subtitles/$id", token, null)
        return parseLibrarySubtitle(body) ?: throw CloudException(200, "字幕内容无法识别")
    }

    fun setShared(token: String, id: Long, shared: Boolean): LibrarySubtitle {
        val body = exchange("PATCH", "/api/library/subtitles/$id", token, """{"shared":$shared}""")
        return parseLibrarySubtitle(body) ?: LibrarySubtitle(id, "", "", "", "", shared, "")
    }

    fun deleteSubtitle(token: String, id: Long) {
        exchange("DELETE", "/api/library/subtitles/$id", token, null)
    }

    fun listTags(token: String): List<LibraryTag> {
        val body = exchange("GET", "/api/library/tags", token, null)
        return parseLibraryTags(body) ?: throw CloudException(200, "标签列表无法识别")
    }

    fun tag(token: String, id: Long): LibraryTag {
        val body = exchange("GET", "/api/library/tags/$id", token, null)
        return parseLibraryTag(body) ?: throw CloudException(200, "标签内容无法识别")
    }

    fun deleteTag(token: String, id: Long) {
        exchange("DELETE", "/api/library/tags/$id", token, null)
    }

    fun putSubtitle(
        token: String,
        videoHash: String,
        videoStem: String,
        subtitleName: String,
        content: String,
        shared: Boolean,
    ): SavedSubtitle {
        ensureSize(content, "字幕正文超过 8MB，服务器不会接收。")
        val json = buildString {
            append("{\"video_hash\":${jsonString(videoHash)},")
            append("\"video_stem\":${jsonString(videoStem)},")
            append("\"subtitle_name\":${jsonString(subtitleName)},")
            append("\"content\":${jsonString(content)},")
            append("\"shared\":$shared}")
        }
        val body = exchange("PUT", "/api/subtitles", token, json)
        return parseSavedSubtitle(body) ?: throw CloudException(200, "上传字幕的响应无法识别")
    }

    fun putScreenshots(token: String, payload: String): String =
        exchange("PUT", "/api/screenshots", token, payload)

    fun putScreenshotImage(token: String, videoHash: String, shotId: String, webp: ByteArray): String {
        if (webp.size > MAX_WEBP_BYTES) throw CloudException(400, "截图需要是 2MB 以内的 WebP")
        val bytes = exchangeRaw(
            "PUT",
            "/api/screenshots/${enc(videoHash)}/${enc(shotId)}/image",
            token,
            webp,
            WEBP,
            "application/json",
        )
        return bytes.toString(Charsets.UTF_8)
    }

    fun getScreenshotImage(token: String, videoHash: String, shotId: String): ByteArray =
        exchangeRaw(
            "GET",
            "/api/screenshots/${enc(videoHash)}/${enc(shotId)}/image",
            token,
            null,
            null,
            "image/webp, */*",
        )

    fun putPlayback(
        token: String,
        videoHash: String,
        videoStem: String,
        sessions: List<PlaybackSession>,
    ): String = exchange("PUT", "/api/playback", token, encodePlaybackUpload(videoHash, videoStem, sessions))

    fun getPlayback(token: String, videoHash: String): String =
        exchange("GET", "/api/playback?video_hash=${enc(videoHash)}", token, null)

    fun putTags(
        token: String,
        videoHash: String,
        videoStem: String,
        subtitleName: String,
        document: CloudTagDocument,
    ): SavedTag {
        val encoded = encodeCloudTagDocument(document)
        ensureSize(encoded, "标签内容超过 8MB，服务器不会接收。")
        val json = buildString {
            append("{\"video_hash\":${jsonString(videoHash)},")
            append("\"video_stem\":${jsonString(videoStem)},")
            append("\"subtitle_name\":${jsonString(subtitleName)},")
            append("\"document\":$encoded}")
        }
        val body = exchange("PUT", "/api/tags", token, json)
        return parseSavedTag(body) ?: throw CloudException(200, "上传标签的响应无法识别")
    }

    private fun parseAuth(body: String): AuthSession =
        parseAuthSession(body) ?: throw CloudException(200, "登录响应无法识别")

    private fun ensureSize(text: String, message: String) {
        if (text.toByteArray(Charsets.UTF_8).size > CLOUD_BODY_LIMIT_BYTES) {
            throw CloudException(413, message)
        }
    }

    private fun exchange(method: String, path: String, token: String?, json: String?): String =
        exchangeRaw(method, path, token, json?.toByteArray(Charsets.UTF_8), JSON, "application/json")
            .toString(Charsets.UTF_8)

    private fun exchangeRaw(
        method: String,
        path: String,
        token: String?,
        bytes: ByteArray?,
        contentType: okhttp3.MediaType?,
        accept: String,
    ): ByteArray {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", accept)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val body = if (bytes == null || method == "GET" || method == "DELETE") {
            null
        } else {
            bytes.toRequestBody(contentType)
        }
        builder.method(method, body)
        try {
            client.newCall(builder.build()).execute().use { response ->
                val payload = response.body?.bytes() ?: ByteArray(0)
                if (response.isSuccessful) return payload
                val text = payload.toString(Charsets.UTF_8)
                val detail = parseErrorDetail(text)
                throw CloudException(response.code, if (detail == "请求失败") "请求失败（${response.code}）" else detail)
            }
        } catch (e: CloudException) {
            throw e
        } catch (_: Exception) {
            throw CloudException(0, "无法连接服务器。")
        }
    }

    private fun authBody(username: String, password: String): String =
        """{"username":${jsonString(username)},"password":${jsonString(password)}}"""

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WEBP = "image/webp".toMediaType()

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
