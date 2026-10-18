package com.coc.suncode.jar.code.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountCooldownTrackerTest {
    @Test
    fun skipsKickedAccountUntilConfiguredCooldownExpires() {
        val tracker = AccountCooldownTracker()
        tracker.startCooldown(accountNumber = 1, durationMinutes = 3, nowMillis = 1_000L)

        assertEquals(2, tracker.findAvailableAccount(listOf(1, 2), nowMillis = 1_001L))
        assertEquals(179_999L, tracker.remainingMillis(accountNumber = 1, nowMillis = 1_001L))
        assertNull(tracker.findAvailableAccount(listOf(1), nowMillis = 180_999L))
        assertEquals(1, tracker.findAvailableAccount(listOf(1), nowMillis = 181_000L))
        assertEquals(0L, tracker.remainingMillis(accountNumber = 1, nowMillis = 181_000L))
    }

    @Test
    fun waitsForSoonestOfSeveralCoolingAccounts() {
        val tracker = AccountCooldownTracker()
        tracker.startCooldown(accountNumber = 1, durationMinutes = 3, nowMillis = 0L)
        tracker.startCooldown(accountNumber = 2, durationMinutes = 1, nowMillis = 0L)

        assertEquals(60_000L, tracker.shortestRemainingMillis(listOf(1, 2), nowMillis = 0L))
    }

    @Test
    fun disabledAccountsDoNotDetermineWaitAndRepeatedKickRenewsOnlyThatAccount() {
        val tracker = AccountCooldownTracker()
        tracker.startCooldown(1, 3, 0L)
        tracker.startCooldown(2, 5, 0L)
        tracker.startCooldown(1, 10, 60_000L)

        assertEquals(600_000L, tracker.shortestRemainingMillis(listOf(1), 60_000L))
        assertEquals(240_000L, tracker.shortestRemainingMillis(listOf(2), 60_000L))
        assertEquals(3, tracker.findAvailableAccount(listOf(1, 2, 3), 60_000L))
        assertEquals(3, tracker.findAvailableAccount(listOf(3, 1, 2), 60_000L))
        assertNull(tracker.findAvailableAccount(emptyList(), 60_000L))
        assertNull(tracker.shortestRemainingMillis(emptyList(), 60_000L))
        assertNull(tracker.findAvailableAccount(listOf(2), 299_999L))
        assertEquals(2, tracker.findAvailableAccount(listOf(2), 300_000L))
    }

    @Test
    fun zeroMinutesDoesNotDelayTheAccount() {
        val tracker = AccountCooldownTracker()
        tracker.startCooldown(accountNumber = 1, durationMinutes = 0, nowMillis = 0L)

        assertEquals(1, tracker.findAvailableAccount(listOf(1), nowMillis = 0L))
    }
}
