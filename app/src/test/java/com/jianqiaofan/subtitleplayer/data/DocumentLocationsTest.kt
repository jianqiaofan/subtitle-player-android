package com.jianqiaofan.subtitleplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLocationsTest {
    @Test
    fun parentIdStripsTheFileName() {
        assertEquals(
            "primary:Movies/课程",
            DocumentLocations.parentId("primary:Movies/课程/课程.mp4"),
        )
    }

    @Test
    fun parentIdIsNullWhenTheFileSitsOnTheVolumeRoot() {
        assertNull(DocumentLocations.parentId("primary:课程.mp4"))
    }

    @Test
    fun directoryMatchesItsOwnTreeAndNestedTrees() {
        assertTrue(DocumentLocations.isWithinTree("primary:Movies", "primary:Movies"))
        assertTrue(DocumentLocations.isWithinTree("primary:Movies", "primary:Movies/课程"))
        assertFalse(DocumentLocations.isWithinTree("primary:Movies", "primary:Movies2/课程"))
        assertFalse(DocumentLocations.isWithinTree("primary:Other", "primary:Movies/课程"))
    }

    @Test
    fun directoryLabelUsesTheLastFolderName() {
        assertEquals("课程", DocumentLocations.directoryLabel("primary:Movies/课程"))
    }
}
