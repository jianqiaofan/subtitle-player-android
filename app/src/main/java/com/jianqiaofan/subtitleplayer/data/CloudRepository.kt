package com.jianqiaofan.subtitleplayer.data

import android.content.Context
import android.net.Uri
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudAnswer
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudMessages
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudOfferLine
import com.jianqiaofan.subtitleplayer.domain.cloud.CloudPrompt
import com.jianqiaofan.subtitleplayer.domain.cloud.HashResolve
import com.jianqiaofan.subtitleplayer.domain.cloud.LocalSubtitleContent
import com.jianqiaofan.subtitleplayer.domain.cloud.NamedText
import com.jianqiaofan.subtitleplayer.domain.cloud.cloudTimeLabel
import com.jianqiaofan.subtitleplayer.domain.cloud.localSubtitleFileName
import com.jianqiaofan.subtitleplayer.domain.cloud.mayOfferShares
import com.jianqiaofan.subtitleplayer.domain.cloud.mergeCloudTags
import com.jianqiaofan.subtitleplayer.domain.cloud.planTagUpload
import com.jianqiaofan.subtitleplayer.domain.cloud.resolveSubtitleHash
import com.jianqiaofan.subtitleplayer.domain.cloud.serverAddressError
import com.jianqiaofan.subtitleplayer.domain.cloud.sha256Hex
import com.jianqiaofan.subtitleplayer.domain.cloud.shouldOfferTagMerge
import com.jianqiaofan.subtitleplayer.domain.cloud.subtitleContentsMatch
import com.jianqiaofan.subtitleplayer.domain.cloud.subtitleDownloadOffers
import com.jianqiaofan.subtitleplayer.domain.cloud.subtitleSuffix
import com.jianqiaofan.subtitleplayer.domain.cloud.validatePassword
import com.jianqiaofan.subtitleplayer.domain.cloud.validateUsername
import com.jianqiaofan.subtitleplayer.domain.cloud.normalizeServer
import com.jianqiaofan.subtitleplayer.domain.bundle.isBundleFolderName
import com.jianqiaofan.subtitleplayer.domain.cloud.VIDEO_HASH_SUFFIX
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PLAYBACK_UPLOAD_LIMIT
import com.jianqiaofan.subtitleplayer.domain.playbacklog.PlaybackSession
import com.jianqiaofan.subtitleplayer.domain.playbacklog.parsePlaybackSnapshot
import com.jianqiaofan.subtitleplayer.domain.timeline.TIMELINE_SUFFIX
import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.model.subtitleFormatOf
import com.jianqiaofan.subtitleplayer.domain.subtitle.loadSubtitleContent
import com.jianqiaofan.subtitleplayer.domain.tags.TAG_DOCUMENT_VERSION
import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleFileNameFromTagFile
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor
import com.jianqiaofan.subtitleplayer.domain.time.utcTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedSubtitle(
    val subtitleName: String,
    val videoHash: String,
    val videoStem: String,
    val suffix: String,
    val content: String,
    val conflict: Boolean,
)

data class UploadPreparation(
    val ready: List<PreparedSubtitle>,
    val notes: List<String>,
)

data class CloudOpenResult(
    val subtitlesChanged: Boolean = false,
    val tagsChanged: Boolean = false,
    val videoHash: String? = null,
)

class CloudRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences(context)
    private val baselines = CloudBaselineStore(context)
    private val folders = CloudFolderFiles(context)
    private val tags = TagDocuments(context)
    private val library = MediaLibrary(context)
    private val client = CloudApi.client()

    suspend fun account() = prefs.cloudAccountOnce()

    suspend fun register(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeServer(server)
        val name = checkedName(server, username, password)
        val session = api(normalized).register(name, password)
        prefs.saveCloudSession(normalized, session.username, password, session.accessToken)
    }

    suspend fun login(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeServer(server)
        val name = checkedName(server, username, password)
        val session = api(normalized).login(name, password)
        prefs.saveCloudSession(normalized, session.username, password, session.accessToken)
    }

    suspend fun listSubtitles() = authorized { cloud, token -> cloud.listSubtitles(token) }

    suspend fun subtitle(id: Long) = authorized { cloud, token -> cloud.subtitle(token, id) }

    suspend fun setShared(ids: List<Long>, shared: Boolean) {
        authorized { cloud, token -> ids.forEach { cloud.setShared(token, it, shared) } }
    }

    suspend fun deleteSubtitles(ids: List<Long>) {
        authorized { cloud, token -> ids.forEach { cloud.deleteSubtitle(token, it) } }
    }

    suspend fun listTags() = authorized { cloud, token -> cloud.listTags(token) }

    suspend fun tag(id: Long) = authorized { cloud, token -> cloud.tag(token, id) }

    suspend fun deleteTags(ids: List<Long>) {
        authorized { cloud, token -> ids.forEach { cloud.deleteTag(token, it) } }
    }

    suspend fun prepareSubtitleUris(uris: List<Uri>): UploadPreparation = withContext(Dispatchers.IO) {
        val ready = mutableListOf<PreparedSubtitle>()
        val notes = mutableListOf<String>()
        val snapshots = linkedMapOf<String, com.jianqiaofan.subtitleplayer.domain.cloud.SyncSnapshot>()
        for (uri in uris) {
            val name = folders.displayName(uri) ?: "字幕"
            if (folders.parentOf(uri) == null) {
                notes += "未上传 $name（${CloudMessages.FOLDER_UNREADABLE}）"
                continue
            }
            considerSubtitle(
                name = name,
                content = folders.readText(uri),
                hashFiles = hashFilesOf(uri),
                ready = ready,
                notes = notes,
                snapshots = snapshots,
            )
        }
        UploadPreparation(ready, notes)
    }

    suspend fun prepareSubtitleTree(treeUri: Uri): UploadPreparation = withContext(Dispatchers.IO) {
        val ready = mutableListOf<PreparedSubtitle>()
        val notes = mutableListOf<String>()
        val snapshots = linkedMapOf<String, com.jianqiaofan.subtitleplayer.domain.cloud.SyncSnapshot>()
        val files = library.listTreeFiles(treeUri).filter { !it.isDirectory }
        for ((parent, children) in files.groupBy { it.parentDocumentUri }) {
            val hashFiles = children.filter { it.name.endsWith(VIDEO_HASH_SUFFIX) }
                .map { NamedText(it.name, folders.readText(it.documentUri).orEmpty()) }
            for (child in children) {
                if (!isSubtitleFile(child.name)) continue
                considerSubtitle(
                    name = child.name,
                    content = folders.readText(child.documentUri),
                    hashFiles = hashFiles,
                    ready = ready,
                    notes = notes,
                    snapshots = snapshots,
                    parent = parent,
                )
            }
        }
        if (files.none { isSubtitleFile(it.name) }) notes += "这个文件夹里没有 .srt 或 .vtt。"
        UploadPreparation(ready, notes)
    }

    suspend fun commitSubtitles(items: List<PreparedSubtitle>, shared: Boolean, skipConflicts: Boolean): String =
        withContext(Dispatchers.IO) {
            val username = prefs.cloudAccountOnce().username
            val lines = mutableListOf<String>()
            authorized { cloud, token ->
                for (item in items) {
                    if (item.conflict && skipConflicts) {
                        lines += "已跳过 ${item.subtitleName}（保留云端已有字幕）"
                        continue
                    }
                    try {
                        val saved = cloud.putSubtitle(token, item.videoHash, item.videoStem, item.subtitleName, item.content, shared)
                        baselines.saveSubtitle(
                            username,
                            item.videoHash,
                            saved.suffix.ifBlank { item.suffix },
                            saved.contentHash.ifBlank { sha256Hex(item.content) },
                            saved.updatedAt,
                        )
                        lines += "已上传 ${item.subtitleName}"
                    } catch (e: CloudException) {
                        lines += "未上传 ${item.subtitleName}（${e.message}）"
                    }
                }
            }
            lines.joinToString("\n").ifBlank { "没有需要上传的字幕" }
        }

    suspend fun uploadTagUris(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val username = prefs.cloudAccountOnce().username
        val lines = mutableListOf<String>()
        authorized { cloud, token ->
            for (uri in uris) {
                val name = folders.displayName(uri) ?: "标签文件"
                val subtitleName = subtitleFileNameFromTagFile(name)
                if (subtitleName == null) {
                    lines += "未上传 $name（文件名不是字幕标签文件）"
                    continue
                }
                val parent = folders.parentOf(uri)
                if (parent == null) {
                    lines += "未上传 $name（${CloudMessages.FOLDER_UNREADABLE}）"
                    continue
                }
                val resolved = resolveSubtitleHash(subtitleName, folders.listHashFiles(parent.treeUri, parent.directoryUri))
                if (resolved is HashResolve.Reject) {
                    lines += "未上传 $name（${resolved.reason}）"
                    continue
                }
                val ok = resolved as HashResolve.Ok
                val suffix = subtitleSuffix(ok.videoStem, subtitleName)
                if (suffix == null) {
                    lines += "未上传 $name（${CloudMessages.NAME_MISMATCH}）"
                    continue
                }
                val document = tags.readDocument(uri)
                if (document == null || document.subtitleFile != subtitleName) {
                    lines += "未上传 $name（标签文件无效，或 subtitle_file 和文件名不一致）"
                    continue
                }
                val baseline = baselines.tag(username, ok.hash, suffix)
                val plan = planTagUpload(document, baseline?.document, utcTimestamp())
                if (plan.localChanged) {
                    tags.writeDocument(uri, TagDocument(TAG_DOCUMENT_VERSION, subtitleName, plan.localEntries))
                }
                val upload = plan.upload
                if (upload == null) {
                    lines += "$name 已是最新"
                    continue
                }
                try {
                    val saved = cloud.putTags(token, ok.hash, ok.videoStem, subtitleName, upload)
                    baselines.saveTag(username, ok.hash, saved.suffix.ifBlank { suffix }, saved.contentHash, saved.updatedAt, saved.document)
                    lines += "已上传 $name"
                } catch (e: CloudException) {
                    lines += "未上传 $name（${e.message}）"
                }
            }
        }
        lines.joinToString("\n").ifBlank { "没有需要上传的标签" }
    }

    suspend fun openSync(
        mediaUri: Uri,
        mediaName: String,
        treeUri: Uri,
        directoryUri: Uri,
        ask: suspend (CloudPrompt) -> CloudAnswer,
    ): CloudOpenResult {
        val hash = withContext(Dispatchers.IO) {
            folders.ensureVideoHash(treeUri, directoryUri, mediaUri, mediaName)
        } ?: return CloudOpenResult()
        val account = prefs.cloudAccountOnce()
        if (account.username.isBlank() || !DeviceNetwork.isOnline(appContext)) return CloudOpenResult(videoHash = hash)
        val stem = mediaStem(mediaName)
        val snapshot = try {
            authorized { cloud, token -> cloud.sync(token, hash) }
        } catch (_: CloudException) {
            return CloudOpenResult(videoHash = hash)
        }
        val local = withContext(Dispatchers.IO) { localSubtitles(treeUri, directoryUri, stem) }
        val offers = subtitleDownloadOffers(stem, snapshot.subtitles, local.map { LocalSubtitleContent(it.fileName, it.content) })
        for (remote in snapshot.subtitles) {
            if (offers.none { it.suffix == remote.suffix }) {
                withContext(Dispatchers.IO) {
                    baselines.saveSubtitle(account.username, hash, remote.suffix, remote.contentHash, remote.updatedAt)
                }
            }
        }
        var downloaded = false
        var subtitlesChanged = false
        val bodyOffers = offers.filter { it.suffix != TIMELINE_SUFFIX }
        val hasLocalSubtitle = local.any { it.valid }
        if (bodyOffers.isNotEmpty() && !hasLocalSubtitle) {
            withContext(Dispatchers.IO) {
                for (offer in bodyOffers) {
                    val written = folders.writeText(directoryUri, offer.fileName, folders.mimeFor(offer.fileName), offer.content)
                    if (written.isSuccess) {
                        baselines.saveSubtitle(account.username, hash, offer.suffix, offer.contentHash, offer.updatedAt)
                        downloaded = true
                        subtitlesChanged = true
                    }
                }
            }
        } else if (bodyOffers.isNotEmpty()) {
            val answer = ask(CloudPrompt.Subtitles(bodyOffers.map { CloudOfferLine(it.fileName, cloudTimeLabel(it.updatedAt)) }))
            if (answer is CloudAnswer.Accept) {
                withContext(Dispatchers.IO) {
                    for (offer in bodyOffers) {
                        val written = folders.writeText(directoryUri, offer.fileName, folders.mimeFor(offer.fileName), offer.content)
                        if (written.isSuccess) {
                            baselines.saveSubtitle(account.username, hash, offer.suffix, offer.contentHash, offer.updatedAt)
                            downloaded = true
                            subtitlesChanged = true
                        }
                    }
                }
            }
        }
        var tagsChanged = false
        val tagOffers = withContext(Dispatchers.IO) {
            snapshot.tags.filter { remote ->
                val localName = localSubtitleFileName(stem, remote.suffix)
                val file = localTag(treeUri, directoryUri, localName)
                val baseline = baselines.tag(account.username, hash, remote.suffix)
                shouldOfferTagMerge(remote.contentHash, baseline?.contentHash, file.exists, file.hasVisibleTags)
            }
        }
        if (tagOffers.isNotEmpty()) {
            val answer = ask(CloudPrompt.Tags(tagOffers.map {
                CloudOfferLine(tagFileNameFor(localSubtitleFileName(stem, it.suffix)), cloudTimeLabel(it.updatedAt))
            }))
            if (answer is CloudAnswer.Accept) {
                withContext(Dispatchers.IO) {
                    for (remote in tagOffers) {
                        val localName = localSubtitleFileName(stem, remote.suffix)
                        val existing = tags.readInFolder(treeUri, directoryUri, localName)
                        val merged = mergeCloudTags(existing, remote.document, localName)
                        val saved = tags.saveInFolder(treeUri, directoryUri, localName, merged)
                        if (saved.isSuccess) {
                            baselines.saveTag(account.username, hash, remote.suffix, remote.contentHash, remote.updatedAt, remote.document)
                            tagsChanged = true
                        }
                    }
                }
            }
        }
        val hasValid = local.any { it.valid } || downloaded
        if (mayOfferShares(hasValid, downloaded)) {
            val people = try {
                authorized { cloud, token -> cloud.shares(token, hash) }
            } catch (_: CloudException) {
                emptyList()
            }
            if (people.isNotEmpty()) {
                val answer = ask(CloudPrompt.Shares(people))
                val username = (answer as? CloudAnswer.Person)?.username
                if (username != null) {
                    val detailed = try {
                        authorized { cloud, token -> cloud.shares(token, hash, username) }
                    } catch (_: CloudException) {
                        emptyList()
                    }
                    withContext(Dispatchers.IO) {
                        for (item in detailed.flatMap { it.subtitles }) {
                            val content = item.content ?: continue
                            if (item.suffix == TIMELINE_SUFFIX) continue
                            val fileName = localSubtitleFileName(stem, item.suffix)
                            val written = folders.writeText(directoryUri, fileName, folders.mimeFor(fileName), content)
                            if (written.isSuccess) subtitlesChanged = true
                        }
                    }
                }
            }
        }
        return CloudOpenResult(subtitlesChanged, tagsChanged, hash)
    }

    suspend fun syncPlayback(
        videoHash: String,
        videoStem: String,
        sessions: List<PlaybackSession>,
    ): List<PlaybackSession>? {
        if (videoHash.isBlank() || !DeviceNetwork.isOnline(appContext)) return null
        if (prefs.cloudAccountOnce().username.isBlank()) return null
        return try {
            var latest = emptyList<PlaybackSession>()
            authorized { cloud, token ->
                if (sessions.isEmpty()) {
                    latest = parsePlaybackSnapshot(cloud.getPlayback(token, videoHash)).orEmpty()
                } else {
                    sessions.chunked(PLAYBACK_UPLOAD_LIMIT).forEach { chunk ->
                        latest = parsePlaybackSnapshot(cloud.putPlayback(token, videoHash, videoStem, chunk)).orEmpty()
                    }
                }
            }
            latest
        } catch (_: Exception) {
            null
        }
    }

    suspend fun uploadEdits(
        videoHash: String,
        videoStem: String,
        treeUri: Uri,
        directoryUri: Uri,
        subtitleNames: List<String>,
        tagSubtitleNames: List<String>,
    ): String = withContext(Dispatchers.IO) {
        if (!DeviceNetwork.isOnline(appContext)) return@withContext ""
        val username = prefs.cloudAccountOnce().username
        if (username.isBlank()) return@withContext ""
        val lines = mutableListOf<String>()
        val localFiles = localSubtitles(treeUri, directoryUri, videoStem)
        val subtitlePayloads = subtitleNames.mapNotNull { name ->
            if (name.endsWith(TIMELINE_SUFFIX)) return@mapNotNull null
            val suffix = subtitleSuffix(videoStem, name)
            if (suffix == null) {
                lines += "未上传 $name（${CloudMessages.NAME_MISMATCH}）"
                return@mapNotNull null
            }
            val content = localFiles.find { it.fileName == name }?.content
            if (content.isNullOrBlank()) {
                lines += "未上传 $name（无法读取文件）"
                return@mapNotNull null
            }
            Triple(name, suffix, content)
        }
        val tagPayloads = tagSubtitleNames.mapNotNull { subtitleName ->
            val suffix = subtitleSuffix(videoStem, subtitleName) ?: return@mapNotNull null
            val document = tags.readInFolder(treeUri, directoryUri, subtitleName) ?: return@mapNotNull null
            val baseline = baselines.tag(username, videoHash, suffix)
            val plan = planTagUpload(document, baseline?.document, utcTimestamp())
            if (plan.localChanged) {
                tags.saveInFolder(
                    treeUri,
                    directoryUri,
                    subtitleName,
                    TagDocument(TAG_DOCUMENT_VERSION, subtitleName, plan.localEntries),
                )
            }
            val upload = plan.upload ?: return@mapNotNull null
            Triple(subtitleName, suffix, upload)
        }
        if (subtitlePayloads.isEmpty() && tagPayloads.isEmpty()) return@withContext lines.joinToString("\n")
        try {
            authorized { cloud, token ->
                val shared = if (subtitlePayloads.isEmpty()) {
                    emptyMap()
                } else {
                    cloud.listSubtitles(token).filter { it.videoHash == videoHash }.associate { it.suffix to it.shared }
                }
                for ((name, suffix, content) in subtitlePayloads) {
                    val saved = cloud.putSubtitle(token, videoHash, videoStem, name, content, shared[suffix] == true)
                    baselines.saveSubtitle(
                        username,
                        videoHash,
                        saved.suffix.ifBlank { suffix },
                        saved.contentHash.ifBlank { sha256Hex(content) },
                        saved.updatedAt,
                    )
                }
                for ((subtitleName, suffix, upload) in tagPayloads) {
                    val saved = cloud.putTags(token, videoHash, videoStem, subtitleName, upload)
                    baselines.saveTag(
                        username,
                        videoHash,
                        saved.suffix.ifBlank { suffix },
                        saved.contentHash,
                        saved.updatedAt,
                        saved.document,
                    )
                }
            }
        } catch (e: CloudException) {
            lines += e.message ?: "上传失败"
        }
        lines.joinToString("\n")
    }

    private suspend fun considerSubtitle(
        name: String,
        content: String?,
        hashFiles: List<NamedText>,
        ready: MutableList<PreparedSubtitle>,
        notes: MutableList<String>,
        snapshots: MutableMap<String, com.jianqiaofan.subtitleplayer.domain.cloud.SyncSnapshot>,
        parent: Uri? = null,
    ) {
        if (content == null) {
            notes += "未上传 $name（无法读取文件）"
            return
        }
        if (hashFiles.isEmpty() && parent != null) {
            notes += "未上传 $name（${CloudMessages.OPEN_VIDEO_FIRST}）"
            return
        }
        when (val resolved = resolveSubtitleHash(name, hashFiles)) {
            is HashResolve.Reject -> notes += "未上传 $name（${resolved.reason}）"
            is HashResolve.Ok -> {
                val suffix = subtitleSuffix(resolved.videoStem, name)
                if (suffix == null) {
                    notes += "未上传 $name（${CloudMessages.NAME_MISMATCH}）"
                    return
                }
                val snapshot = snapshots.getOrPut(resolved.hash) {
                    authorized { cloud, token -> cloud.sync(token, resolved.hash) }
                }
                val remote = snapshot.subtitles.find { it.suffix == suffix }
                if (remote != null && subtitleContentsMatch(content, remote.content, remote.contentHash)) {
                    notes += "$name 已是最新"
                    baselines.saveSubtitle(prefs.cloudAccountOnce().username, resolved.hash, suffix, remote.contentHash, remote.updatedAt)
                    return
                }
                ready += PreparedSubtitle(
                    subtitleName = name,
                    videoHash = resolved.hash,
                    videoStem = resolved.videoStem,
                    suffix = suffix,
                    content = content,
                    conflict = remote != null,
                )
            }
        }
    }

    private fun hashFilesOf(uri: Uri): List<NamedText> {
        val parent = folders.parentOf(uri) ?: return emptyList()
        val direct = folders.listHashFiles(parent.treeUri, parent.directoryUri)
        val nested = library.listFolder(parent.treeUri, parent.directoryId)
            .filter { it.isDirectory && isBundleFolderName(it.displayName) }
            .flatMap { folders.listHashFiles(parent.treeUri, it.documentUri) }
        return direct + nested
    }

    private fun localSubtitles(treeUri: Uri, directoryUri: Uri, stem: String): List<LocalCueFile> {
        val directoryId = try {
            android.provider.DocumentsContract.getDocumentId(directoryUri)
        } catch (_: Exception) {
            return emptyList()
        }
        return library.listFolder(treeUri, directoryId)
            .filter { !it.isDirectory && subtitleSuffix(stem, it.displayName) != null }
            .map { child ->
                val content = folders.readText(child.documentUri).orEmpty()
                val format = subtitleFormatOf(child.displayName)
                val valid = format != null && loadSubtitleContent(content, format).isNotEmpty()
                LocalCueFile(child.displayName, content, valid)
            }
    }

    private fun localTag(treeUri: Uri, directoryUri: Uri, subtitleFileName: String): LocalTagState {
        val directoryId = try {
            android.provider.DocumentsContract.getDocumentId(directoryUri)
        } catch (_: Exception) {
            return LocalTagState(false, false)
        }
        val name = tagFileNameFor(subtitleFileName)
        val child = library.listFolder(treeUri, directoryId).firstOrNull { it.displayName == name && !it.isDirectory }
            ?: return LocalTagState(false, false)
        val document = tags.readDocument(child.documentUri, subtitleFileName)
        val visible = document?.entries?.any { it.tags.isNotEmpty() } == true
        return LocalTagState(true, visible)
    }

    private fun checkedName(server: String, username: String, password: String): String {
        serverAddressError(server)?.let { throw CloudException(400, it) }
        val name = validateUsername(username)
        name.error?.let { throw CloudException(400, it) }
        validatePassword(password)?.let { throw CloudException(400, it) }
        return name.username
    }

    suspend fun <T> online(block: (CloudApi, String, String) -> T): T? {
        val account = prefs.cloudAccountOnce()
        if (account.username.isBlank() || !DeviceNetwork.isOnline(appContext)) return null
        return try {
            authorized { api, token -> block(api, token, account.username) }
        } catch (e: CloudException) {
            if (e.status == 0) null else throw e
        }
    }

    private suspend fun <T> authorized(block: (CloudApi, String) -> T): T = withContext(Dispatchers.IO) {
        val account = prefs.cloudAccountOnce()
        val cloud = api(account.server)
        var token = account.token
        if (token.isBlank() || account.tokenUsername != account.username) {
            token = relogin(account, cloud)
        }
        try {
            block(cloud, token)
        } catch (e: CloudException) {
            if (e.status != 401) throw e
            block(cloud, relogin(account, cloud))
        }
    }

    private suspend fun relogin(account: com.jianqiaofan.subtitleplayer.domain.cloud.StoredCloudAccount, cloud: CloudApi): String {
        if (account.username.isBlank() || account.password.isBlank()) throw CloudException(401, "请先登录")
        val session = try {
            cloud.login(account.username, account.password)
        } catch (e: CloudException) {
            if (e.status == 401) prefs.saveCloudSession(account.server, account.username, "", "")
            throw e
        }
        prefs.saveCloudSession(account.server, session.username, account.password, session.accessToken)
        return session.accessToken
    }

    private fun api(server: String) = CloudApi(normalizeServer(server), client)

    private data class LocalCueFile(val fileName: String, val content: String, val valid: Boolean)
    private data class LocalTagState(val exists: Boolean, val hasVisibleTags: Boolean)
}
