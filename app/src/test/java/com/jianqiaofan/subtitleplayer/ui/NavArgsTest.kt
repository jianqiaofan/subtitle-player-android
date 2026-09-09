package com.jianqiaofan.subtitleplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavArgsTest {
    @Test
    fun roundTripsSafDocumentUri() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2Fceshi-zimu%2F%E6%B5%8B%E8%AF%95%E8%A7%86%E9%A2%91%E5%AD%97%E5%B9%95/document/primary%3ADownload%2Fceshi-zimu%2F%E6%B5%8B%E8%AF%95%E8%A7%86%E9%A2%91%E5%AD%97%E5%B9%95%2FUnit%201%20section%201-5%20%E6%97%A5%E6%B1%89%E7%89%88.mp4"
        val encoded = encodeNavArg(uri)
        assertFalse(encoded.contains('/'))
        assertEquals(uri, decodeNavArg(encoded))
    }

    @Test
    fun extraPercentDecodeWouldBreakSafPath() {
        val original =
            "content://authority/tree/primary%3ADownload%2Ffolder/document/primary%3ADownload%2Ffolder%2Fvideo.mp4"
        val broken = java.net.URLDecoder.decode(original, Charsets.UTF_8)
        assertEquals(
            "content://authority/tree/primary:Download/folder/document/primary:Download/folder/video.mp4",
            broken,
        )
        assertEquals(original, decodeNavArg(encodeNavArg(original)))
    }
}
