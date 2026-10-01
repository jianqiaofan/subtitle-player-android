package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.screenshot.MAX_WEBP_BYTES
import com.jianqiaofan.subtitleplayer.domain.screenshot.RemoteScreenshot
import com.jianqiaofan.subtitleplayer.domain.screenshot.SCREENSHOT_VERSION
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotDocument
import com.jianqiaofan.subtitleplayer.domain.screenshot.ScreenshotShot
import com.jianqiaofan.subtitleplayer.domain.screenshot.ShotImageWork
import com.jianqiaofan.subtitleplayer.domain.screenshot.baselineIdsForSync
import com.jianqiaofan.subtitleplayer.domain.screenshot.encodeScreenshotPut
import com.jianqiaofan.subtitleplayer.domain.screenshot.frameIndexAt
import com.jianqiaofan.subtitleplayer.domain.screenshot.imageNameFor
import com.jianqiaofan.subtitleplayer.domain.screenshot.mergeScreenshots
import com.jianqiaofan.subtitleplayer.domain.screenshot.newScreenshotId
import com.jianqiaofan.subtitleplayer.domain.screenshot.parseRemoteScreenshots
import com.jianqiaofan.subtitleplayer.domain.screenshot.planShotImage
import com.jianqiaofan.subtitleplayer.domain.screenshot.screenshotSyncRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ScreenshotRepository(context: Context) {
    private val appContext = context.applicationContext
    private val files = ScreenshotFiles(appContext)
    private val baselines = ScreenshotBaselineStore(appContext)
    private val cloud = CloudRepository(appContext)
    private val folders = CloudFolderFiles(appContext)
    private val prefs = AppPreferences(appContext)
    private val gate = Mutex()
    /** Shot ids the UI cancelled; capture must not leave them on disk. Guarded by [gate]. */
    private val discardedIds = mutableSetOf<String>()

    suspend fun imageBytes(treeUri: Uri, bundleUri: Uri, shotId: String): ByteArray? = gate.withLock {
        withContext(Dispatchers.IO) {
            val state = files.load(treeUri, bundleUri)
            val named = state.document.screenshots.find { it.id == shotId }?.image?.takeIf { it.isNotBlank() }
            val fileName = named ?: listOf(imageNameFor(shotId, png = true), imageNameFor(shotId, png = false))
                .firstOrNull { it in state.names }
            if (fileName == null) null else files.readImage(treeUri, bundleUri, fileName)
        }
    }

    suspend fun read(treeUri: Uri, bundleUri: Uri): List<ScreenshotShot> = gate.withLock {
        withContext(Dispatchers.IO) {
            val state = files.load(treeUri, bundleUri)
            if (state.unreadable) emptyList() else state.document.screenshots
        }
    }

    suspend fun capture(
        treeUri: Uri,
        bundleUri: Uri,
        mediaUri: Uri,
        timeMs: Long,
        frameRate: Float?,
        title: String = "",
        shotId: String = newScreenshotId(),
    ): Pair<String?, String?> = gate.withLock {
        withContext(Dispatchers.IO) {
            if (shotId in discardedIds) {
                discardedIds -= shotId
                deleteShotFiles(treeUri, bundleUri, shotId)
                return@withContext null to null
            }
            coroutineContext.ensureActive()
            val bitmap = grabVideoFrame(appContext, mediaUri, timeMs)
                ?: return@withContext null to "无法截取当前画面"
            coroutineContext.ensureActive()
            if (shotId in discardedIds) {
                discardedIds -= shotId
                bitmap.recycle()
                return@withContext null to null
            }
            val png = try {
                encodePng(bitmap)
            } finally {
                bitmap.recycle()
            }
            coroutineContext.ensureActive()
            if (shotId in discardedIds) {
                discardedIds -= shotId
                return@withContext null to null
            }
            val id = shotId
            val now = System.currentTimeMillis()
            val name = imageNameFor(id, png = true)
            if (!files.writeImage(treeUri, bundleUri, name, "image/png", png)) {
                return@withContext null to "无法保存截图"
            }
            if (shotId in discardedIds) {
                discardedIds -= shotId
                deleteShotFiles(treeUri, bundleUri, shotId)
                return@withContext null to null
            }
            coroutineContext.ensureActive()
            val state = files.load(treeUri, bundleUri)
            if (state.unreadable) {
                files.deleteImage(treeUri, bundleUri, name)
                return@withContext null to "截图说明无法识别"
            }
            val shot = ScreenshotShot(
                id = id,
                title = title.take(4_000),
                time = timeMs.coerceAtLeast(0L) / 1000.0,
                frame = frameIndexAt(timeMs.coerceAtLeast(0L) / 1000.0, frameRate),
                image = name,
                createdAt = now,
                updatedAt = now,
                notes = emptyList(),
            )
            val document = state.document.copy(screenshots = state.document.screenshots + shot)
            if (!files.write(treeUri, bundleUri, document)) {
                files.deleteImage(treeUri, bundleUri, name)
                val left = files.load(treeUri, bundleUri)
                if (!left.manifestPresent && left.names.isEmpty()) files.clear(treeUri, bundleUri)
                return@withContext null to "无法保存截图"
            }
            if (shotId in discardedIds) {
                discardedIds -= shotId
                removeShotFromManifest(
                    treeUri,
                    bundleUri,
                    shotId,
                    mediaUri,
                    mediaName = "",
                    mediaPath = "",
                    knownHash = null,
                    wipeBaseline = false,
                )
                return@withContext null to null
            }
            id to null
        }
    }

    /**
     * Cancel an in-flight or just-finished create: mark [shotId] discarded so a racing
     * [capture] rolls back, and always delete that id's image files / manifest row.
     */
    suspend fun discardCapture(
        treeUri: Uri,
        bundleUri: Uri,
        shotId: String,
        mediaUri: Uri,
        mediaName: String,
        mediaPath: String,
        knownHash: String?,
    ): String? = gate.withLock {
        withContext(Dispatchers.IO) {
            discardedIds += shotId
            deleteShotFiles(treeUri, bundleUri, shotId)
            removeShotFromManifest(
                treeUri,
                bundleUri,
                shotId,
                mediaUri,
                mediaName,
                mediaPath,
                knownHash,
                wipeBaseline = true,
            )
        }
    }

    suspend fun save(treeUri: Uri, bundleUri: Uri, shot: ScreenshotShot): String? = gate.withLock {
        withContext(Dispatchers.IO) {
            val state = files.load(treeUri, bundleUri)
            if (state.unreadable) return@withContext "截图说明无法识别"
            val shots = state.document.screenshots.toMutableList()
            val index = shots.indexOfFirst { it.id == shot.id }
            if (index < 0) return@withContext "找不到这张截图"
            shots[index] = shot
            if (!files.write(treeUri, bundleUri, state.document.copy(screenshots = shots))) "无法保存截图" else null
        }
    }

    suspend fun delete(
        treeUri: Uri,
        bundleUri: Uri,
        shotId: String,
        mediaUri: Uri,
        mediaName: String,
        mediaPath: String,
        knownHash: String?,
    ): String? = gate.withLock {
        withContext(Dispatchers.IO) {
            deleteShotFiles(treeUri, bundleUri, shotId)
            removeShotFromManifest(
                treeUri,
                bundleUri,
                shotId,
                mediaUri,
                mediaName,
                mediaPath,
                knownHash,
                wipeBaseline = true,
            )
        }
    }

    private fun deleteShotFiles(treeUri: Uri, bundleUri: Uri, shotId: String) {
        files.deleteImage(treeUri, bundleUri, imageNameFor(shotId, png = true))
        files.deleteImage(treeUri, bundleUri, imageNameFor(shotId, png = false))
    }

    private suspend fun removeShotFromManifest(
        treeUri: Uri,
        bundleUri: Uri,
        shotId: String,
        mediaUri: Uri,
        mediaName: String,
        mediaPath: String,
        knownHash: String?,
        wipeBaseline: Boolean,
    ): String? {
        val state = files.load(treeUri, bundleUri)
        if (state.unreadable) return "截图说明无法识别"
        if (state.document.screenshots.none { it.id == shotId }) return null
        val remaining = state.document.screenshots.filterNot { it.id == shotId }
        if (remaining.isEmpty()) {
            if (wipeBaseline) {
                val username = prefs.cloudAccountOnce().username
                val hash = knownHash?.takeIf { it.isNotBlank() }
                    ?: folders.ensureVideoHash(treeUri, bundleUri, mediaUri, mediaName)
                if (!username.isNullOrBlank() && !hash.isNullOrBlank()) {
                    baselines.markWiped(username, hash, mediaPath)
                }
            }
            files.clear(treeUri, bundleUri)
        } else if (!files.write(treeUri, bundleUri, state.document.copy(screenshots = remaining))) {
            return "无法保存截图"
        }
        return null
    }

    suspend fun sync(
        treeUri: Uri,
        bundleUri: Uri,
        mediaUri: Uri,
        mediaName: String,
        mediaPath: String,
        knownHash: String?,
    ): String? = gate.withLock {
        withContext(Dispatchers.IO) {
            val account = prefs.cloudAccountOnce()
            if (account.username.isBlank() || !DeviceNetwork.isOnline(appContext)) return@withContext null
            val hash = knownHash?.takeIf { it.isNotBlank() }
                ?: folders.ensureVideoHash(treeUri, bundleUri, mediaUri, mediaName)
                ?: return@withContext null
            val state = files.load(treeUri, bundleUri)
            if (state.unreadable) return@withContext "截图说明无法识别"
            val record = baselines.get(account.username, hash)
            val baseline = baselineIdsForSync(state.manifestPresent, record?.wiped == true, record?.ids.orEmpty())
            val shots = state.document.screenshots
            screenshotSyncRejection(shots)?.let { return@withContext it }
            val payload = encodeScreenshotPut(hash, mediaStem(mediaName), shots, baseline)
            val body = try {
                cloud.online { api, token, _ -> api.putScreenshots(token, payload) }
            } catch (e: CloudException) {
                return@withContext e.message
            } ?: return@withContext null
            val remote = parseRemoteScreenshots(body) ?: return@withContext "同步响应无法识别"
            val localNow = files.load(treeUri, bundleUri)
            if (localNow.unreadable) return@withContext "截图说明无法识别"
            val merged = mergeScreenshots(shots.map { it.id }.toSet(), localNow.document.screenshots, remote)
            val names = localNow.names.toMutableSet()
            val remoteById = remote.associateBy { it.id }
            var imageError: String? = null
            val updated = mutableListOf<ScreenshotShot>()
            for (shot in merged) {
                val item = remoteById[shot.id]
                if (item == null) {
                    updated += shot
                } else {
                    updated += placeImage(treeUri, bundleUri, mediaUri, hash, shot, item, names) { message ->
                        if (imageError == null) imageError = message
                    }
                }
            }
            val written = files.write(treeUri, bundleUri, ScreenshotDocument(SCREENSHOT_VERSION, updated))
            if (!written && updated.isNotEmpty()) return@withContext imageError ?: "无法保存截图"
            baselines.save(account.username, hash, remote.map { it.id }, mediaPath)
            imageError
        }
    }

    private suspend fun placeImage(
        treeUri: Uri,
        bundleUri: Uri,
        mediaUri: Uri,
        videoHash: String,
        shot: ScreenshotShot,
        remote: RemoteScreenshot,
        names: MutableSet<String>,
        onError: (String) -> Unit,
    ): ScreenshotShot {
        val pngName = imageNameFor(shot.id, png = true)
        val webpName = imageNameFor(shot.id, png = false)
        if (pngName in names && webpName in names) {
            files.deleteImage(treeUri, bundleUri, webpName)
            names.remove(webpName)
        }
        return when (planShotImage(pngName in names, shot.frame, remote.hasImage)) {
            ShotImageWork.None -> shot.copy(image = if (pngName in names) pngName else if (webpName in names) webpName else shot.image)
            ShotImageWork.Upload -> {
                uploadPng(treeUri, bundleUri, videoHash, shot.id, pngName, onError)
                shot.copy(image = pngName)
            }
            ShotImageWork.Extract -> {
                val bitmap = grabVideoFrame(appContext, mediaUri, (shot.time * 1000.0).toLong())
                if (bitmap != null) {
                    val png = try {
                        encodePng(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    if (files.writeImage(treeUri, bundleUri, pngName, "image/png", png)) {
                        names += pngName
                        if (webpName in names) {
                            files.deleteImage(treeUri, bundleUri, webpName)
                            names.remove(webpName)
                        }
                        if (!remote.hasImage) uploadPng(treeUri, bundleUri, videoHash, shot.id, pngName, onError)
                        shot.copy(image = pngName)
                    } else {
                        downloadOrKeep(treeUri, bundleUri, videoHash, shot, remote.hasImage, pngName, webpName, names, onError)
                    }
                } else {
                    downloadOrKeep(treeUri, bundleUri, videoHash, shot, remote.hasImage, pngName, webpName, names, onError)
                }
            }
            ShotImageWork.Download -> downloadOrKeep(treeUri, bundleUri, videoHash, shot, true, pngName, webpName, names, onError)
        }
    }

    private suspend fun downloadOrKeep(
        treeUri: Uri,
        bundleUri: Uri,
        videoHash: String,
        shot: ScreenshotShot,
        hasImage: Boolean,
        pngName: String,
        webpName: String,
        names: MutableSet<String>,
        onError: (String) -> Unit,
    ): ScreenshotShot {
        if (pngName in names) return shot.copy(image = pngName)
        if (!hasImage) return shot.copy(image = if (webpName in names) webpName else shot.image)
        val bytes = try {
            cloud.online { api, token, _ -> api.getScreenshotImage(token, videoHash, shot.id) }
        } catch (e: CloudException) {
            if (e.status != 404) onError(e.message ?: "没有这张截图")
            null
        } ?: return shot.copy(image = if (webpName in names) webpName else "")
        return if (pngName !in names && files.writeImage(treeUri, bundleUri, webpName, "image/webp", bytes)) {
            names += webpName
            shot.copy(image = webpName)
        } else {
            shot.copy(image = if (pngName in names) pngName else if (webpName in names) webpName else "")
        }
    }

    private suspend fun uploadPng(
        treeUri: Uri,
        bundleUri: Uri,
        videoHash: String,
        shotId: String,
        pngName: String,
        onError: (String) -> Unit,
    ) {
        val bytes = files.readImage(treeUri, bundleUri, pngName) ?: return
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            onError("无法压缩截图")
            return
        }
        val webp = try {
            encodeWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (webp.size > MAX_WEBP_BYTES) {
            onError("截图需要是 2MB 以内的 WebP")
            return
        }
        try {
            cloud.online { api, token, _ -> api.putScreenshotImage(token, videoHash, shotId, webp) }
        } catch (e: CloudException) {
            onError(e.message ?: "截图需要是 2MB 以内的 WebP")
        }
    }
}
