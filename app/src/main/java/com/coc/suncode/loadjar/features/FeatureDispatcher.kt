package com.coc.suncode.loadjar.features

import com.coc.suncode.interfaces.FeatureResult

/** Availability and lifetime rules are independent of Android and feature implementation details. */
class FeatureDispatcher(
    private val enabled: (String) -> Boolean,
    private val load: (String) -> (suspend (String, Int) -> FeatureResult)
) {
    private val actions = mutableMapOf<String, suspend (String, Int) -> FeatureResult>()

    fun reload() = synchronized(actions) { actions.clear() }

    suspend fun execute(id: String, action: String, account: Int): FeatureResult {
        if (!enabled(id)) return if (id == "login") FeatureResult.FAILED else FeatureResult.SKIPPED
        val execute = synchronized(actions) { actions.getOrPut(id) { load(id) } }
        // Propagate cancellation and failures; replaying partially executed touches is unsafe.
        return execute(action, account)
    }
}
