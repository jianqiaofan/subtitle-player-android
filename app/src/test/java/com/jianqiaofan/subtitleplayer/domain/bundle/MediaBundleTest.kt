package com.jianqiaofan.subtitleplayer.domain.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBundleTest {
    @Test
    fun bundleNameKeepsTheVideoExtension() {
        assertEquals("电脑.mp4.data", bundleFolderName("电脑.mp4"))
        assertEquals("电脑.mkv.data", bundleFolderName("电脑.mkv"))
        assertFalse(bundleFolderName("电脑.mp4") == "电脑.mp4")
        assertTrue(isBundleFolderName("电脑.mp4.data"))
        assertFalse(isBundleFolderName("电脑.mp4"))
        assertFalse(isBundleFolderName(".data"))
    }

    @Test
    fun longerStemClaimsTheSubtitleAndNotesStayPut() {
        val siblings = listOf(
            "第1课.mp4",
            "第1课_复习.mp4",
            "第1课_复习.srt",
            "第1课.srt",
            "第1课_笔记.txt",
            "生词表.txt",
        )
        assertEquals(listOf("第1课.srt"), subtitlesBesideVideo("第1课.mp4", siblings))
        assertEquals(listOf("第1课_复习.srt"), subtitlesBesideVideo("第1课_复习.mp4", siblings))
    }

    @Test
    fun migrationMovesSubtitleTagAndHashAndReportsSameName() {
        val plan = planBundleMigration(
            "电脑.mp4",
            listOf("电脑.mp4", "电脑.srt", "电脑.srt.tags.json", "电脑.mp4.videohash.json", "电脑_笔记.txt"),
            listOf("电脑_中文.srt"),
        )
        assertEquals(listOf("电脑.srt", "电脑.srt.tags.json", "电脑.mp4.videohash.json"), plan.moveIntoBundle)
        assertTrue(plan.conflicts.isEmpty())
        assertFalse(plan.moveIntoBundle.any { it.contains("笔记") })
    }

    @Test
    fun sameNameIsAConflictAndExistingHashIsNotMovedAgain() {
        val plan = planBundleMigration(
            "电脑.mp4",
            listOf("电脑.mp4", "电脑.srt", "电脑.srt.tags.json", "电脑.mp4.videohash.json"),
            listOf("电脑.srt", "电脑.mp4.videohash.json"),
        )
        assertEquals(listOf("电脑.srt"), plan.conflicts)
        assertEquals(listOf("电脑.mp4.videohash.json"), plan.deleteBeside)
        assertTrue(plan.moveIntoBundle.isEmpty())
    }

    @Test
    fun keepingTheBundleCopyDeletesTheCopyBesideTheVideo() {
        val resolution = resolveNameConflict("电脑.srt", keepBundleCopy = true, bundleHasTag = true, besideHasTag = true)
        assertEquals(listOf("电脑.srt", "电脑.srt.tags.json"), resolution.deleteBeside)
        assertTrue(resolution.moveIntoBundle.isEmpty())
        assertTrue(resolution.deleteInBundle.isEmpty())
    }

    @Test
    fun keepingTheCopyBesideTheVideoReplacesTheBundle() {
        val resolution = resolveNameConflict("电脑.srt", keepBundleCopy = false, bundleHasTag = true, besideHasTag = false)
        assertEquals(listOf("电脑.srt", "电脑.srt.tags.json"), resolution.deleteInBundle)
        assertEquals(listOf("电脑.srt"), resolution.moveIntoBundle)
    }
}
