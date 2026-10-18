package com.coc.suncode.core.qq

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QqAuthorizationReplayTest {
    private fun grant(account: String) = QqAuthorizationRecord(
        "access-$account", "openid-$account", "pay-$account", 3600, System.currentTimeMillis()
    )

    @Test
    fun switchingFromAToBAndBackUsesTheSelectedAccountEveryTime() = runBlocking {
        val backend = CompanionFixture()
        val replay = QqAuthorizationReplay(backend)
        val a = grant("a")
        val b = grant("b")
        for (record in listOf(a, b, a)) {
            replay.prepare(record)
            assertEquals(record.toolInput(), backend.authorizeGame())
        }
    }

    @Test
    fun staleHigherPriorityInputCannotEnableAnotherLogin() = runBlocking {
        val backend = CompanionFixture()
        val replay = QqAuthorizationReplay(backend)
        val a = grant("a")
        replay.prepare(a)
        assertEquals(a.toolInput(), backend.authorizeGame())
        val enabledBefore = backend.replayEnabledCount
        backend.discardExternalWrites = true
        val error = runCatching { replay.prepare(grant("b")) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(enabledBefore, backend.replayEnabledCount)
    }

    @Test
    fun expiredAccountCannotReplaceAnExistingAccount() = runBlocking {
        val backend = CompanionFixture()
        val replay = QqAuthorizationReplay(backend)
        val a = grant("a")
        replay.prepare(a)
        assertEquals(a.toolInput(), backend.authorizeGame())
        val enabledBefore = backend.replayEnabledCount
        val error = runCatching { replay.prepare(grant("b").copy(capturedAt = 1L)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(enabledBefore, backend.replayEnabledCount)
        assertEquals(a.toolInput(), backend.authorizeGame())
    }

    /** Emulate the APK's external-file priority and process-local preference cache. */
    private class CompanionFixture : QqReplayBackend {
        override val externalInputPath = "fixture-external-input"
        private var externalInput: String? = null
        private var preferenceInput = ""
        private var processInput: String? = null
        var replayEnabledCount = 0
        var discardExternalWrites = false

        override suspend fun stopTool() { processInput = null }
        override suspend fun putPreference(name: String, key: String, value: String) {
            when (name to key) {
                "shsj" to "raw_text" -> preferenceInput = value
                "ms" to "pd_key" -> if (value == "1") replayEnabledCount++
                else -> error("Unexpected fixture preference")
            }
        }
        override suspend fun writeText(path: String, content: String) {
            check(path == externalInputPath)
            if (!discardExternalWrites) externalInput = content
        }
        override suspend fun readText(path: String): String {
            check(path == externalInputPath)
            return externalInput.orEmpty()
        }
        fun authorizeGame(): String = processInput ?: (externalInput ?: preferenceInput).also { processInput = it }
    }
}
