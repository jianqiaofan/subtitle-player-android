package com.jianqiaofan.subtitleplayer.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AppReleaseTest {
    private val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    @Test
    fun newerVersionCodeOnly() {
        assertTrue(isNewerRelease(1, 2))
        assertFalse(isNewerRelease(2, 2))
        assertFalse(isNewerRelease(3, 2))
    }

    @Test
    fun autoPromptOncePerRemoteVersion() {
        assertTrue(shouldAutoPromptUpdate(5, 0))
        assertTrue(shouldAutoPromptUpdate(5, 4))
        assertFalse(shouldAutoPromptUpdate(5, 5))
        assertFalse(shouldAutoPromptUpdate(5, 6))
    }

    @Test
    fun parsesReleaseAndRejectsBadHash() {
        val release = parseAppRelease(
            """
            {"versionCode":2,"versionName":"1.0.2","url":"https://subtitle.gcsfg.work/releases/app.apk","sha256":"$sha","notes":"修正时间轴"}
            """.trimIndent(),
        )
        assertEquals(2, release?.versionCode)
        assertEquals("1.0.2", release?.versionName)
        assertEquals("修正时间轴", release?.notes)
        assertNull(parseAppRelease("""{"versionCode":"2","versionName":"1.0.2","url":"https://subtitle.gcsfg.work/releases/app.apk","sha256":"$sha","notes":""}"""))
        assertNull(parseAppRelease("""{"versionCode":2,"versionName":"1.0.2","url":"https://subtitle.gcsfg.work/releases/app.apk","sha256":"abc","notes":""}"""))
    }

    @Test
    fun placeholderIsUpToDateUntilARealRelease() {
        val release = parseAppRelease(
            """{"versionCode":0,"versionName":"0","url":"https://subtitle.gcsfg.work/releases/app.apk","sha256":"","notes":"还没有可安装的版本"}""",
        )
        assertTrue(decideRelease(1, release!!) is ReleaseDecision.UpToDate)
        val newer = release.copy(versionCode = 2, sha256 = sha)
        assertTrue(decideRelease(1, newer) is ReleaseDecision.Download)
        assertTrue(decideRelease(1, newer.copy(sha256 = "")) is ReleaseDecision.Invalid)
        assertTrue(decideRelease(1, newer.copy(url = "https://evil.example/app.apk")) is ReleaseDecision.Invalid)
    }

    @Test
    fun onlyOfficialReleaseUrls() {
        assertTrue(releaseUrlAllowed("https://subtitle.gcsfg.work/releases/app.apk"))
        assertFalse(releaseUrlAllowed("http://subtitle.gcsfg.work/releases/app.apk"))
        assertFalse(releaseUrlAllowed("https://evil.example/releases/app.apk"))
        assertFalse(releaseUrlAllowed("https://subtitle.gcsfg.work/other/app.apk"))
        assertFalse(releaseUrlAllowed("https://user:pass@subtitle.gcsfg.work/releases/app.apk"))
        assertFalse(releaseUrlAllowed("https://subtitle.gcsfg.work/releases/../secret.apk"))
        assertFalse(releaseUrlAllowed("https://subtitle.gcsfg.work/releases/"))
    }

    @Test
    fun hashesHello() {
        assertEquals(sha, sha256Hex(ByteArrayInputStream("hello".toByteArray())))
    }
}
