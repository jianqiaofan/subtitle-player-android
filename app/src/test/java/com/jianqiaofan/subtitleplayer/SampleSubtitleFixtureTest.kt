package com.jianqiaofan.subtitleplayer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSubtitleFixtureTest {
    @Test
    fun sampleSrtFromHandoverIsOnClasspath() {
        val stream = javaClass.classLoader?.getResourceAsStream("subtitles/sample.srt")
        assertNotNull("缺少 app/src/test/resources/subtitles/sample.srt", stream)
        val text = stream!!.bufferedReader().readText()
        assertTrue(text.contains("欢迎使用字幕学习播放器 Demo"))
        assertTrue(text.contains("-->"))
    }
}
