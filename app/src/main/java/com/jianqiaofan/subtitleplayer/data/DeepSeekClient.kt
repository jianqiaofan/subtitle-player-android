package com.jianqiaofan.subtitleplayer.data

import com.jianqiaofan.subtitleplayer.domain.json.jsonString
import com.jianqiaofan.subtitleplayer.domain.json.parseJson
import com.jianqiaofan.subtitleplayer.domain.json.str
import com.jianqiaofan.subtitleplayer.domain.json.obj
import com.jianqiaofan.subtitleplayer.domain.json.arr
import com.jianqiaofan.subtitleplayer.domain.subtitle.DEFAULT_DEEPSEEK_BASE_URL
import com.jianqiaofan.subtitleplayer.domain.subtitle.DEFAULT_DEEPSEEK_MODEL
import com.jianqiaofan.subtitleplayer.domain.subtitle.DeepSeekConfig
import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DeepSeekException(message: String) : Exception(message)

class DeepSeekClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun complete(config: DeepSeekConfig, system: String, user: String): String {
        val base = config.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_DEEPSEEK_BASE_URL }
        val model = config.model.trim().ifBlank { DEFAULT_DEEPSEEK_MODEL }
        val payload = buildString {
            append("{\"model\":").append(jsonString(model))
            append(",\"messages\":[")
            append("{\"role\":\"system\",\"content\":").append(jsonString(system)).append("},")
            append("{\"role\":\"user\",\"content\":").append(jsonString(user)).append("}")
            append("],\"stream\":false}")
        }
        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw DeepSeekException("无法连接 DeepSeek")
        }
        response.use { http ->
            val body = http.body?.string().orEmpty()
            if (!http.isSuccessful) throw DeepSeekException("DeepSeek API 请求失败（HTTP ${http.code}）")
            return chatContent(body)
        }
    }

    private fun chatContent(body: String): String {
        val root = try {
            parseJson(body) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: throw DeepSeekException("DeepSeek API 返回格式异常")
        val message = root.map.arr("choices")
            ?.firstOrNull()
            ?.let { it as? JsonValue.Obj }
            ?.map
            ?.obj("message")
        val content = message?.str("content")?.trim().orEmpty()
        if (content.isEmpty()) throw DeepSeekException("DeepSeek API 返回内容为空")
        return content
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
