package com.coc.suncode.jar.code.universal

/** Tracks temporary account cooldowns while the automation process is running. */
class AccountCooldownTracker {
    private val cooldownEndsAtMillis = mutableMapOf<Int, Long>()

    @Synchronized
    fun startCooldown(accountNumber: Int, durationMinutes: Int, nowMillis: Long) {
        val durationMillis = durationMinutes.coerceAtLeast(0).toLong() * MILLIS_PER_MINUTE
        cooldownEndsAtMillis[accountNumber] = nowMillis + durationMillis
    }

    @Synchronized
    fun remainingMillis(accountNumber: Int, nowMillis: Long): Long {
        val remaining = (cooldownEndsAtMillis[accountNumber] ?: return 0L) - nowMillis
        if (remaining <= 0L) {
            cooldownEndsAtMillis.remove(accountNumber)
            return 0L
        }
        return remaining
    }

    @Synchronized
    fun findAvailableAccount(accountNumbers: Iterable<Int>, nowMillis: Long): Int? =
        accountNumbers.firstOrNull { remainingMillis(it, nowMillis) == 0L }

    @Synchronized
    fun shortestRemainingMillis(accountNumbers: Iterable<Int>, nowMillis: Long): Long? =
        accountNumbers.mapNotNull { remainingMillis(it, nowMillis).takeIf { remaining -> remaining > 0L } }.minOrNull()

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
