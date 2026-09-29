package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.tags.TagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.TagEntry
import com.jianqiaofan.subtitleplayer.domain.tags.TagOp
import com.jianqiaofan.subtitleplayer.domain.tags.parseTagDocument
import com.jianqiaofan.subtitleplayer.domain.tags.encodeTagDocument
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncTest {
    @Test
    fun usernameRules() {
        assertEquals("用户名中间不能有空格。", validateUsername("ab c").error)
        assertEquals("zhangsan", validateUsername("  zhangsan  ").username)
        assertNull(validateUsername("zhangsan").error)
        assertTrue(validateUsername("ab").error!!.contains("3 到 32"))
        assertTrue(validateUsername("...").error!!.contains("字母或数字"))
        assertEquals("密码需要 8 到 72 个字符。", validatePassword("short"))
        assertNull(validatePassword("12345678"))
        assertEquals("https://subtitle.gcsfg.work", normalizeServer("  "))
        assertTrue(serverAddressError("http://subtitle.gcsfg.work")!!.contains("HTTPS"))
    }

    @Test
    fun contentHashMatchesServer() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256Hex("hello"),
        )
    }

    @Test
    fun hashCacheAcceptsNanosecondsFromDesktop() {
        val record = VideoHashRecord("ab".repeat(32), 100, 1_700_000_000_000_000_000L)
        assertTrue(isSha256Hex(record.hash))
        assertTrue(hashCacheValid(record, 100, 1_700_000_000_000L))
        assertFalse(hashCacheValid(record, 99, 1_700_000_000_000L))
        assertFalse(hashCacheValid(record.copy(hash = "AB".repeat(32)), 100, 1_700_000_000_000L))
    }

    @Test
    fun longerVideoStemClaimsSubtitle() {
        val shortHash = hashFile("第1课.mp4", "aa".repeat(32))
        val longHash = hashFile("第1课_复习.mp4", "bb".repeat(32))
        val review = resolveSubtitleHash("第1课_复习.srt", listOf(shortHash, longHash))
        assertEquals("bb".repeat(32), (review as HashResolve.Ok).hash)
        assertEquals("第1课_复习", review.videoStem)
        val lesson = resolveSubtitleHash("第1课.srt", listOf(shortHash, longHash)) as HashResolve.Ok
        assertEquals("aa".repeat(32), lesson.hash)
        val onlyShort = resolveSubtitleHash("第1课_复习.srt", listOf(shortHash)) as HashResolve.Ok
        assertEquals("第1课", onlyShort.videoStem)
    }

    @Test
    fun hashResolveExplainsFailures() {
        assertEquals(
            CloudMessages.OPEN_VIDEO_FIRST,
            (resolveSubtitleHash("第1课.srt", emptyList()) as HashResolve.Reject).reason,
        )
        assertEquals(
            CloudMessages.BAD_HASH_NAME,
            (resolveSubtitleHash("第1课.srt", listOf(NamedText("第1课.videohash.json", """{"hash":"${"ab".repeat(32)}","size":1,"mtime_ns":1}"""))) as HashResolve.Reject).reason,
        )
        assertEquals(
            CloudMessages.NAME_MISMATCH,
            (resolveSubtitleHash("别的.srt", listOf(hashFile("第1课.mp4", "ab".repeat(32)))) as HashResolve.Reject).reason,
        )
        assertEquals(
            CloudMessages.BAD_HASH,
            (resolveSubtitleHash("第1课.srt", listOf(hashFile("第1课.mp4", "ZZ"))) as HashResolve.Reject).reason,
        )
        val ambiguous = resolveSubtitleHash(
            "第1课.srt",
            listOf(hashFile("第1课.mp4", "aa".repeat(32)), hashFile("第1课.mkv", "bb".repeat(32))),
        ) as HashResolve.Reject
        assertEquals(CloudMessages.AMBIGUOUS, ambiguous.reason)
    }

    @Test
    fun downloadUsesCurrentStemAndSkipsSameContent() {
        assertEquals("_中文.srt", subtitleSuffix("复习", "复习_中文.srt"))
        assertEquals(".srt", subtitleSuffix("复习", "复习.srt"))
        val remote = listOf(
            RemoteSubtitle("第1课_中文.srt", "_中文.srt", "甲", sha256Hex("甲"), "2026-09-29T08:00:00Z"),
            RemoteSubtitle("第1课_英文.srt", "_英文.srt", "en", sha256Hex("en"), "2026-09-29T08:00:00Z"),
        )
        val offers = subtitleDownloadOffers(
            "复习",
            remote,
            listOf(LocalSubtitleContent("复习_英文.srt", "en")),
        )
        assertEquals(listOf("复习_中文.srt"), offers.map { it.fileName })
        assertEquals(
            "2026-09-29 16:00",
            cloudTimeLabel("2026-09-29T08:00:00Z", ZoneId.of("Asia/Shanghai")),
        )
        assertFalse(mayOfferShares(hasValidLocalSubtitle = true, downloadedOwn = false))
        assertFalse(mayOfferShares(hasValidLocalSubtitle = false, downloadedOwn = true))
        assertTrue(mayOfferShares(hasValidLocalSubtitle = false, downloadedOwn = false))
    }

    @Test
    fun tagMergeKeepsNewerOpAndLocalExtras() {
        val local = TagDocument(
            1,
            "复习_中文.srt",
            listOf(
                entry("s1", listOf("重点", "自定义"), listOf(TagOp("重点", true, "2026-09-29T10:00:00Z")), "本地", "2026-09-29T10:00:00Z"),
            ),
        )
        val cloud = CloudTagDocument(
            "第1课_中文.srt",
            listOf(
                CloudTagEntry("s1", 1, 1.0, 2.0, "同一句", listOf(TagOp("重点", false, "2026-09-29T08:00:00Z")), "云端", "2026-09-29T08:00:00Z"),
                CloudTagEntry("s2", 2, 2.0, 3.0, "新句", listOf(TagOp("难点", true, "2026-09-29T09:00:00Z")), "", "2026-09-29T09:00:00Z"),
            ),
        )
        val merged = mergeCloudTags(local, cloud, "复习_中文.srt")
        assertEquals("复习_中文.srt", merged.subtitleFile)
        assertEquals(listOf("重点", "自定义"), merged.entries[0].tags)
        assertEquals("本地", merged.entries[0].note)
        assertEquals(listOf("难点"), merged.entries[1].tags)

        val olderLocal = local.copy(
            entries = listOf(entry("s1", listOf("重点"), listOf(TagOp("重点", true, "2026-09-29T07:00:00Z")), "旧", "2026-09-29T07:00:00Z")),
        )
        val removed = mergeCloudTags(olderLocal, cloud, "复习_中文.srt")
        assertFalse("重点" in removed.entries[0].tags)
        assertEquals("云端", removed.entries[0].note)
    }

    @Test
    fun uploadSendsOnlyChangesAndKeepsDeletion() {
        val now = "2026-09-29T11:00:00Z"
        val local = TagDocument(
            1,
            "复习_中文.srt",
            listOf(
                entry(
                    "s1",
                    listOf("难点"),
                    listOf(
                        TagOp("重点", true, "2026-09-29T08:00:00Z"),
                        TagOp("重点", false, "2026-09-29T10:00:00Z"),
                        TagOp("难点", true, "2026-09-29T10:00:00Z"),
                    ),
                    "备注",
                    "2026-09-29T08:00:00.000Z",
                ),
            ),
        )
        val baseline = CloudTagDocument(
            "第1课_中文.srt",
            listOf(
                CloudTagEntry(
                    "s1",
                    1,
                    1.0,
                    2.0,
                    "同一句",
                    listOf(TagOp("重点", true, "2026-09-29T08:00:00.000Z")),
                    "备注",
                    "2026-09-29T08:00:00Z",
                ),
            ),
        )
        val plan = planTagUpload(local, baseline, now)
        val upload = plan.upload!!
        assertEquals(1, upload.entries.size)
        assertEquals(listOf("重点", "难点"), upload.entries[0].tagOps.map { it.name })
        assertFalse(upload.entries[0].tagOps.first { it.name == "重点" }.present)
        assertTrue(upload.entries[0].tagOps.first { it.name == "难点" }.present)

        val same = planTagUpload(
            local.copy(
                entries = listOf(
                    entry("s1", listOf("重点"), listOf(TagOp("重点", true, "2026-09-29T08:00:00Z")), "备注", "2026-09-29T08:00:00Z"),
                ),
            ),
            baseline,
            now,
        )
        assertNull(same.upload)
    }

    @Test
    fun tagOfferFollowsBaselineAndVisibleTags() {
        assertFalse(shouldOfferTagMerge("abc", "abc", localExists = true, localHasVisibleTags = true))
        assertTrue(shouldOfferTagMerge("abc", "old", localExists = true, localHasVisibleTags = true))
        assertTrue(shouldOfferTagMerge("abc", "abc", localExists = false, localHasVisibleTags = false))
        assertTrue(shouldOfferTagMerge("abc", "abc", localExists = true, localHasVisibleTags = false))
    }

    @Test
    fun localTagFileKeepsDeletionOps() {
        val doc = TagDocument(
            1,
            "a.srt",
            listOf(
                TagEntry("id1", 1, 0.0, 1.0, "你好你好你好", emptyList(), "", listOf(TagOp("重点", false, "2026-09-29T08:00:00Z")), ""),
            ),
        )
        val parsed = parseTagDocument(encodeTagDocument(doc), "a.srt")
        assertEquals(emptyList<String>(), parsed!!.entries[0].tags)
        assertFalse(parsed.entries[0].tagOps.single().present)
    }

    @Test
    fun parsesSyncPayload() {
        val snapshot = parseSyncSnapshot(
            """
            {"video_hash":"ab","subtitles":[{"subtitle_name":"probe.srt","subtitle_suffix":".srt","content":"hello","content_hash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","updated_at":"2026-09-29T09:11:52Z"}],"tags":[]}
            """.trimIndent(),
        )
        assertEquals("hello", snapshot!!.subtitles.single().content)
        assertEquals("用户名已存在", parseErrorDetail("""{"detail":"用户名已存在"}"""))
    }

    private fun hashFile(videoName: String, hash: String) = NamedText(
        "$videoName.videohash.json",
        """{"hash":"$hash","size":10,"mtime_ns":20}""",
    )

    private fun entry(id: String, tags: List<String>, ops: List<TagOp>, note: String, noteAt: String) =
        TagEntry(id, 1, 1.0, 2.0, "同一句", tags, note, ops, noteAt)
}
