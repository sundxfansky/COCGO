package com.coc.suncode.jar.code.universal.smalltools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountCooldownsTest {
    @Test
    fun kickedAccountIsSkippedWhileOtherAccountsContinueInCircularOrder() {
        val tracker = AccountCooldownTracker { 0L }
        tracker.start(1, 3)
        assertEquals(2, tracker.select(listOf(1, 2, 3)).account)
        assertEquals(3, tracker.select(listOf(3, 1, 2)).account)
        assertEquals(0L, tracker.select(listOf(1, 2)).waitMillis)
    }

    @Test
    fun allCoolingAccountsWaitForTheEarliestDeadlineAndResumeAtItsBoundary() {
        var now = 0L
        val tracker = AccountCooldownTracker { now }
        tracker.start(1, 3)
        tracker.start(2, 5)
        val waiting = tracker.select(listOf(2, 1))
        assertNull(waiting.account)
        assertEquals(180_000L, waiting.waitMillis)
        now = 179_999L
        assertEquals(1L, tracker.select(listOf(2, 1)).waitMillis)
        now = 180_000L
        assertEquals(1, tracker.select(listOf(2, 1)).account)
        now = 300_000L
        assertEquals(2, tracker.select(listOf(2, 1)).account)
    }

    @Test
    fun disabledAccountsDoNotDetermineTheWaitingTimeAndRepeatedKickRenewsOnlyThatAccount() {
        var now = 0L
        val tracker = AccountCooldownTracker { now }
        tracker.start(1, 3)
        tracker.start(2, 5)
        now = 60_000L
        tracker.start(1, 10)
        assertEquals(600_000L, tracker.select(listOf(1)).waitMillis)
        assertEquals(240_000L, tracker.select(listOf(2)).waitMillis)
        assertEquals(3, tracker.select(listOf(1, 2, 3)).account)
        assertNull(tracker.select(emptyList()).account)
        assertEquals(0L, tracker.select(emptyList()).waitMillis)
    }

    @Test
    fun invalidDurationCannotCreateAnUnboundedCooldown() {
        val tracker = AccountCooldownTracker { 0L }
        assertThrows(IllegalArgumentException::class.java) { tracker.start(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { tracker.start(1, 1441) }
        assertThrows(IllegalArgumentException::class.java) { tracker.start(0, 3) }
    }
}
