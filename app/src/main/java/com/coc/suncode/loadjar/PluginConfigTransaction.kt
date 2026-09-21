package com.coc.suncode.loadjar

import androidx.compose.runtime.MutableState

/** Preserve both state identity and values when a plugin fails during initialization. */
internal fun <T> withConfigRollback(
    states: MutableMap<String, MutableState<String>>,
    initialize: () -> T
): T {
    val originalStates = states.toMap()
    val originalValues = originalStates.mapValues { it.value.value }
    try {
        return initialize()
    } catch (error: Throwable) {
        states.keys.removeAll { it !in originalStates }
        originalStates.forEach { (key, state) ->
            state.value = originalValues.getValue(key)
            states[key] = state
        }
        throw error
    }
}
