package com.coc.suncode.core.qq

import org.junit.Assert.assertEquals
import org.junit.Test

class QqAuthorizationTimeTest {
    @Test
    fun expiredTimeIsZeroMinutes() {
        assertEquals("0分钟", formatQqAuthorizationTime(0L))
        assertEquals("0分钟", formatQqAuthorizationTime(-1L))
    }

    @Test
    fun activeTimeRoundsUpWithoutShowingSeconds() {
        assertEquals("1分钟", formatQqAuthorizationTime(1L))
        assertEquals("30分钟", formatQqAuthorizationTime(30L * 60_000L))
        assertEquals("31分钟", formatQqAuthorizationTime(30L * 60_000L + 1L))
    }

    @Test
    fun hourAndDayBoundariesAreCarriedCorrectly() {
        assertEquals("1小时 0分钟", formatQqAuthorizationTime(3_599_999L))
        assertEquals("1天 0小时 0分钟", formatQqAuthorizationTime(86_399_999L))
        assertEquals("2天 3小时 5分钟", formatQqAuthorizationTime((2L * 1440L + 185L) * 60_000L))
    }
}
