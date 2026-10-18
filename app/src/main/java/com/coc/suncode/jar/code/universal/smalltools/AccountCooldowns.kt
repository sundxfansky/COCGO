package com.coc.suncode.jar.code.universal.smalltools

/** Monotonic deadlines keep kicked accounts out of rotation while other accounts continue. */
internal class AccountCooldownTracker(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    data class Selection(val account: Int?, val waitMillis: Long)
    private val deadlines = mutableMapOf<Int, Long>()

    @Synchronized
    fun start(account: Int, minutes: Int) {
        require(account > 0 && minutes in 1..1440)
        deadlines[account] = nowMillis() + minutes * 60_000L
    }

    @Synchronized
    fun select(enabledAccounts: List<Int>): Selection {
        val now = nowMillis()
        deadlines.entries.removeAll { it.value <= now }
        val ready = enabledAccounts.firstOrNull { it !in deadlines }
        if (ready != null || enabledAccounts.isEmpty()) return Selection(ready, 0L)
        return Selection(null, enabledAccounts.minOf { deadlines.getValue(it) - now })
    }
}

// Keep deadlines across automation restarts that reuse the same loaded plugin.
internal val accountCooldowns = AccountCooldownTracker()
