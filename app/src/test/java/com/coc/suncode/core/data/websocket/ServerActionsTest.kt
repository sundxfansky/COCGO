package com.coc.suncode.core.data.websocket

import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.fileactions.FileHelper
import com.coc.suncode.jar.ui.schema.SchemaExporter
import com.google.gson.JsonObject
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerActionsTest {
    private class FakeTransport : ServerTransport {
        val messages = mutableListOf<Any>()
        val listeners = mutableListOf<(String) -> Unit>()
        var accepts = true

        override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            listeners += onMessage
            onOpen()
        }
        override fun sendAction(action: Any): Boolean { messages += action; return accepts }
        override fun close() = Unit
        fun reply(data: String, connection: Int = listeners.lastIndex) {
            listeners[connection](Gson().toJson(mapOf("status" to "success", "data" to data)))
        }
        fun initialize() { reply("true"); reply("{\"saved\":\"original\"}") }
    }

    @Test
    fun startupRepliesCannotCompleteBusinessRequestOrReinitializeOnOtherReads() = runBlocking {
        val transport = FakeTransport()
        var callbacks = 0
        val actions = ServerActions(transport, "/test/") { callbacks++ }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { actions.sendActionSync("read-other") }
        assertEquals(2, transport.messages.size)
        transport.reply("true")
        assertFalse(pending.isCompleted)
        assertEquals(0, callbacks)
        transport.reply("{\"saved\":\"original\"}")
        // Yield to the waiter after startup has completed.
        yield()
        assertEquals(3, transport.messages.size)
        assertFalse(pending.isCompleted)
        transport.reply("{\"unrelated\":\"value\"}")
        assertEquals("success", pending.await()?.get("status")?.asString)
        assertEquals("original", actions.getValue("saved"))
        assertEquals(1, callbacks)
    }

    @Test
    fun cancellationPropagatesAndLateReplyCannotCompleteNextRequest() = runBlocking {
        val transport = FakeTransport()
        val actions = ServerActions(transport, baseDir = "/test/")
        transport.initialize()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { actions.sendActionSync("cancelled") }
        cancelled.cancel()
        try { cancelled.await(); throw AssertionError("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(2, transport.listeners.size)
        transport.initialize()
        val next = async(start = CoroutineStart.UNDISPATCHED) { actions.sendActionSync("next") }
        transport.reply("stale", connection = 0)
        assertFalse(next.isCompleted)
        transport.reply("current")
        assertEquals("current", next.await()?.get("data")?.asString)
    }

    @Test
    fun failedSendReturnsWithoutWaitingAndStartsFreshConnection() = runBlocking {
        val transport = FakeTransport()
        val actions = ServerActions(transport, baseDir = "/test/")
        transport.initialize()
        transport.accepts = false
        assertNull(actions.sendActionSync("failed"))
        assertEquals(2, transport.listeners.size)
    }

    @Test
    fun timeoutReconnectsAndCloseDoesNotReconnect() = runBlocking {
        val transport = FakeTransport()
        val actions = ServerActions(transport, baseDir = "/test/")
        transport.initialize()
        assertNull(actions.sendActionSync("timeout", timeout = 20))
        assertEquals(2, transport.listeners.size)
        actions.close()
        assertNull(actions.sendActionSync("after-close"))
        assertEquals(2, transport.listeners.size)
    }

    @Test
    fun configIsPublishedOnlyAfterSuccessfulPersistence() = runBlocking {
        val transport = FakeTransport()
        val actions = ServerActions(transport, baseDir = "/test/")
        transport.initialize()
        val updated = Gson().fromJson("{\"saved\":\"updated\"}", JsonObject::class.java)
        val failed = async(start = CoroutineStart.UNDISPATCHED) { actions.persistConfig(updated) }
        transport.listeners.last()("{\"status\":\"error\",\"message\":\"disk full\"}")
        assertFalse(failed.await())
        assertEquals("original", actions.getValue("saved"))
        val saved = async(start = CoroutineStart.UNDISPATCHED) { actions.persistConfig(updated) }
        transport.reply("20")
        assertTrue(saved.await())
        assertEquals("updated", actions.getValue("saved"))
    }

    @Test
    fun fileExistenceUsesDataAndSchemaSaveReportsFailure() = runBlocking {
        val transport = FakeTransport()
        val actions = ServerActions(transport, baseDir = "/test/")
        transport.initialize()
        val previous = GlobalVars.serverActions
        GlobalVars.serverActions = actions
        try {
            val exists = async(start = CoroutineStart.UNDISPATCHED) { FileHelper.checkExists("/missing") }
            transport.reply("false")
            assertFalse(exists.await())
            val save = async(start = CoroutineStart.UNDISPATCHED) {
                SchemaExporter.saveSchemaViaServer("/test/", "sun_config.json", keys = emptyList())
            }
            transport.listeners.last()("{\"status\":\"error\"}")
            assertFalse(save.await())
            assertEquals("original", actions.getValue("saved"))
        } finally { GlobalVars.serverActions = previous }
    }
}
