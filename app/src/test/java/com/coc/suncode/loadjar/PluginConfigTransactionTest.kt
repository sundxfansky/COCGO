package com.coc.suncode.loadjar

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginConfigTransactionTest {
    @Test fun failedInitializationRestoresRemovedAndReplacedStates() {
        val old = mutableStateOf("saved")
        val removed = mutableStateOf("draft")
        val states = mutableMapOf("old" to old, "removed" to removed)
        val failure = IllegalStateException("incompatible configuration")

        val thrown = assertThrows(IllegalStateException::class.java) {
            withConfigRollback(states) {
                old.value = "partial"
                states["old"] = mutableStateOf("replacement")
                states.remove("removed")
                states["new"] = mutableStateOf("new schema")
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(setOf("old", "removed"), states.keys)
        assertSame(old, states["old"])
        assertSame(removed, states["removed"])
        assertEquals("saved", old.value)
        assertEquals("draft", removed.value)
    }

    @Test fun successfulInitializationKeepsNewConfiguration() {
        val states = mutableMapOf("old" to mutableStateOf("saved"))
        val result = withConfigRollback(states) {
            states["old"]!!.value = "updated"
            states["new"] = mutableStateOf("default")
            "ready"
        }
        assertEquals("ready", result)
        assertEquals("updated", states["old"]!!.value)
        assertEquals("default", states["new"]!!.value)
    }
}
