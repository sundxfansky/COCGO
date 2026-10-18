package com.coc.suncode.core.qq

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.data.websocket.ServerTransport
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Use a separate private directory so device tests never replace the user's saved grants. */
@RunWith(AndroidJUnit4::class)
class QqMultiAccountTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "qq-multi-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
            override fun startActivity(intent: Intent) {
                throw AssertionError("An unattended account without a valid grant must not open an Activity")
            }
        }
    }

    @After
    fun tearDown() { check(directory.deleteRecursively()) }

    private fun grant(account: String) = QqAuthorizationRecord(
        "access-$account", "openid-$account", "pay-$account", 3600, System.currentTimeMillis()
    )

    @Test
    fun reopenedStoreKeepsAccountsSeparateAcrossRotationAndRefresh() {
        val a = grant("a")
        val b = grant("b")
        QqAuthorizationStore(context).apply {
            write(1, a)
            write(2, b)
        }
        val reopened = QqAuthorizationStore(context)
        assertEquals(listOf(a, b, a), listOf(1, 2, 1).map { reopened.read(it) })
        val refreshedB = b.copy(accessToken = "refreshed-b")
        reopened.write(2, refreshedB)
        assertEquals(a, QqAuthorizationStore(context).read(1))
        assertEquals(refreshedB, QqAuthorizationStore(context).read(2))
        assertNull(reopened.read(3))
    }

    @Test
    fun missingOrExpiredAccountSkipsWithoutOpeningTheToolOrChangingOtherGrants() = runBlocking {
        val a = grant("a")
        val expiredB = grant("b").copy(capturedAt = 1L)
        val store = QqAuthorizationStore(context)
        store.write(1, a)
        store.write(2, expiredB)
        assertFalse(QqAuthorizationManager.loginAccount(context, 3))
        assertFalse(QqAuthorizationManager.loginAccount(context, 2))
        assertEquals(a, store.read(1))
        assertEquals(expiredB, store.read(2))
        assertNull(store.read(3))
    }

    @Test
    fun clearingRemovesAllSavedGrantsAndRequestsCompanionCacheCleanup() = runBlocking {
        val store = QqAuthorizationStore(context)
        store.write(1, grant("a"))
        store.write(99, grant("b"))
        // Isolate both storage and shell transport: this test must never clear the real companion.
        val transport = CleanupTransport()
        val originalActions = GlobalVars.serverActions
        val actions = ServerActions(transport, "/test/")
        transport.initialize()
        GlobalVars.serverActions = actions
        try {
            QqAuthorizationManager.clearSavedAuthorizations(context)
            assertNull(QqAuthorizationStore(context).read(1))
            assertNull(QqAuthorizationStore(context).read(99))
            assertFalse(File(directory, "qq_authorizations").exists())
            assertTrue(transport.commands.any { it.contains("aw_temp.txt") && it.startsWith("rm -f --") })
            val clearIndex = transport.commands.indexOfFirst { it.startsWith("pm clear --user") }
            // A verified installed tool must be stopped and cleared before removing the external input.
            if (clearIndex >= 0) {
                assertTrue(transport.commands[clearIndex - 1].startsWith("am force-stop --user"))
                assertTrue(clearIndex < transport.commands.indexOfFirst { it.contains("aw_temp.txt") })
            }
            store.write(1, grant("new"))
            assertEquals("access-new", store.read(1)?.accessToken)
        } finally {
            GlobalVars.serverActions = originalActions
            actions.close()
        }
    }

    private class CleanupTransport : ServerTransport {
        val commands = mutableListOf<String>()
        private var initializing = true
        private lateinit var receive: (String) -> Unit
        override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            receive = onMessage
            onOpen()
        }
        override fun sendAction(action: Any): Boolean {
            if (!initializing) {
                val command = (action as Map<*, *>)["command"] as String
                commands += command
                receive(Gson().toJson(mapOf("status" to "success", "exitCode" to 0, "data" to "Success")))
            }
            return true
        }
        override fun close() = Unit
        fun initialize() {
            receive(Gson().toJson(mapOf("status" to "success", "data" to "true")))
            receive(Gson().toJson(mapOf("status" to "success", "data" to "{}")))
            initializing = false
        }
    }
}
