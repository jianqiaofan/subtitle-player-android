package com.jianqiaofan.subtitleplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepShutdownFormatTest {
    @Test
    fun remainUnderAnHourOmitsHours() {
        assertEquals("0:05", formatSleepRemain(5))
        assertEquals("29:00", formatSleepRemain(29 * 60))
    }

    @Test
    fun remainAtTwoHoursKeepsHours() {
        assertEquals("2:00:00", formatSleepRemain(2 * 60 * 60))
    }
}
