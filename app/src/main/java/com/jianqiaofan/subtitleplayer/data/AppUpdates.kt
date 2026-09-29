package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.jianqiaofan.subtitleplayer.domain.update.AppRelease
import com.jianqiaofan.subtitleplayer.domain.update.LATEST_RELEASE_URL
import com.jianqiaofan.subtitleplayer.domain.update.MSG_BAD_MANIFEST
import com.jianqiaofan.subtitleplayer.domain.update.MSG_BAD_URL
import com.jianqiaofan.subtitleplayer.domain.update.MSG_DAMAGED
import com.jianqiaofan.subtitleplayer.domain.update.MSG_OFFLINE
import com.jianqiaofan.subtitleplayer.domain.update.ReleaseDecision
import com.jianqiaofan.subtitleplayer.domain.update.decideRelease
import com.jianqiaofan.subtitleplayer.domain.update.parseAppRelease
import com.jianqiaofan.subtitleplayer.domain.cloud.isSha256Hex
import com.jianqiaofan.subtitleplayer.domain.update.releaseUrlAllowed
import com.jianqiaofan.subtitleplayer.domain.update.sha256Hex
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateException(message: String) : Exception(message)

sealed class UpdateCheck {
    data object Latest : UpdateCheck()
    data class Available(val release: AppRelease) : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

sealed class PreparedApk {
    data class Ready(val file: File) : PreparedApk()
    data class Failed(val message: String) : PreparedApk()
}

class AppUpdates(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun inspect(): UpdateCheck {
        val body = try {
            httpText(LATEST_RELEASE_URL, "无法读取版本说明")
        } catch (error: UpdateException) {
            return UpdateCheck.Failed(error.message ?: MSG_OFFLINE)
        }
        val release = parseAppRelease(body) ?: return UpdateCheck.Failed(MSG_BAD_MANIFEST)
        return when (val decision = decideRelease(localVersionCode(), release)) {
            ReleaseDecision.UpToDate -> UpdateCheck.Latest
            is ReleaseDecision.Download -> UpdateCheck.Available(decision.release)
            is ReleaseDecision.Invalid -> UpdateCheck.Failed(decision.message)
        }
    }

    fun prepareInstall(release: AppRelease): PreparedApk {
        if (!isSha256Hex(release.sha256) || !releaseUrlAllowed(release.url)) {
            return PreparedApk.Failed(if (!isSha256Hex(release.sha256)) MSG_BAD_MANIFEST else MSG_BAD_URL)
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, fileName(release))
        if (target.isFile && sha256File(target).equals(release.sha256, ignoreCase = true)) {
            return PreparedApk.Ready(target)
        }
        val partial = File(directory, "update.download")
        try {
            download(release.url, partial)
            val actual = sha256File(partial)
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                partial.delete()
                return PreparedApk.Failed(MSG_DAMAGED)
            }
            if (target.exists() && !target.delete()) {
                partial.delete()
                return PreparedApk.Failed("无法保存更新包。")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            directory.listFiles()?.forEach { file ->
                if (file != target && (file.extension == "apk" || file.name == "update.download")) file.delete()
            }
            return PreparedApk.Ready(target)
        } catch (error: UpdateException) {
            partial.delete()
            return PreparedApk.Failed(error.message ?: MSG_OFFLINE)
        }
    }

    fun localVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionCodeCompat().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun httpText(url: String, failure: String): String {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) throw UpdateException(MSG_BAD_URL)
                if (!response.isSuccessful) throw UpdateException("$failure（HTTP ${response.code}）。")
                response.body?.string().orEmpty()
            }
        } catch (error: UpdateException) {
            throw error
        } catch (_: Exception) {
            throw UpdateException(MSG_OFFLINE)
        }
    }

    private fun download(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) throw UpdateException(MSG_BAD_URL)
                if (!response.isSuccessful) throw UpdateException("无法下载更新（HTTP ${response.code}）。")
                val body = response.body ?: throw UpdateException("无法下载更新。")
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (error: UpdateException) {
            dest.delete()
            throw error
        } catch (_: Exception) {
            dest.delete()
            throw UpdateException(MSG_OFFLINE)
        }
    }

    private fun fileName(release: AppRelease): String = "update-${release.versionCode}-${release.sha256}.apk"

    private fun sha256File(file: File): String = file.inputStream().use { sha256Hex(it) }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
