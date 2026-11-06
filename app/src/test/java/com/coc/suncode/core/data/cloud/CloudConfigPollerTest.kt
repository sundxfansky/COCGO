package com.coc.suncode.core.data.cloud

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudConfigPollerTest {
    private class FakeServer {
        var version = 1L
        var name = "Server name"
        var config = JsonObject().apply { addProperty("account_count", "2") }
        val requests = mutableListOf<String>()
        suspend fun request(path: String, method: String, payload: JsonObject?): JsonObject {
            requests += "$method $path"
            return when {
                path == "/devices" -> JsonObject().apply { add("device", JsonObject().apply { addProperty("name", name) }) }
                method == "PATCH" -> {
                    name = payload!!.get("name").asString
                    JsonObject().apply { add("device", JsonObject().apply { addProperty("name", name) }) }
                }
                path.endsWith("/version") -> JsonObject().apply { addProperty("version", version) }
                else -> JsonObject().apply { addProperty("version", version); add("config", config.deepCopy()) }
            }
        }
    }

    @Test
    fun unchangedVersionsFetchNoFullSnapshotAndChangedRevisionAppliesOnce() = runBlocking {
        val server = FakeServer()
        val applied = mutableListOf<Long>()
        val acknowledged = mutableListOf<Long>()
        var registeredName = ""
        val poller = CloudConfigPoller("device-a", "Local name", false, -1, server::request,
            { _, version -> applied += version; true }, { version, _ -> acknowledged += version }, { registeredName = it })
        repeat(3) { poller.checkOnce() }
        assertEquals(1, server.requests.count { it == "GET /devices/device-a/config" })
        assertEquals(3, server.requests.count { it == "GET /devices/device-a/config/version" })
        assertEquals(1, server.requests.count { it == "POST /devices" })
        assertFalse(server.requests.any { it.startsWith("PATCH") })
        assertEquals("Server name", registeredName)
        server.version = 2
        poller.checkOnce()
        poller.checkOnce()
        assertEquals(listOf(1L, 2L), applied)
        assertEquals(applied, acknowledged)
    }

    @Test
    fun failedPersistenceDoesNotAcknowledgeAndRetriesTheSameVersion() = runBlocking {
        val server = FakeServer()
        var persistSucceeds = false
        var acknowledgements = 0
        val poller = CloudConfigPoller("device-a", "Name", false, -1, server::request,
            { _, _ -> persistSucceeds }, { _, _ -> acknowledgements++ }, {})
        try { poller.checkOnce(); throw AssertionError("Expected failed persistence") } catch (_: IOException) { }
        assertNull(poller.appliedVersion())
        assertEquals(0, acknowledgements)
        persistSucceeds = true
        assertEquals(1L, poller.checkOnce().version)
        assertEquals(1, acknowledgements)
        assertEquals(2, server.requests.count { it == "GET /devices/device-a/config" })
    }

    @Test
    fun cancellationBeforePersistenceCompletionCannotAcknowledge() = runBlocking {
        val server = FakeServer()
        var acknowledged = false
        val poller = CloudConfigPoller("device-a", "Name", false, -1, server::request,
            { _, _ -> throw CancellationException("connection replaced") }, { _, _ -> acknowledged = true }, {})
        try { poller.checkOnce(); throw AssertionError("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(acknowledged)
        assertNull(poller.appliedVersion())
    }

    @Test
    fun emptyUnpublishedDeviceKeepsLocalConfigurationUntilPublication() = runBlocking {
        val server = FakeServer().apply { version = 0; config = JsonObject() }
        var applied = 0
        val poller = CloudConfigPoller("device-a", "Name", false, -1, server::request,
            { _, _ -> applied++; true }, { _, _ -> }, {})
        assertEquals("waiting", poller.checkOnce().status)
        assertEquals("waiting", poller.checkOnce().status)
        assertEquals(0, applied)
        assertEquals(1, server.requests.count { it == "GET /devices/device-a/config" })
        server.version = 1
        assertEquals("synced", poller.checkOnce().status)
        assertEquals(1, applied)
    }

    @Test
    fun devicesHaveIndependentPathsAndAcknowledgements() = runBlocking {
        val server = FakeServer()
        val a = CloudConfigPoller("device-a", "A", false, 1, server::request, { _, _ -> true }, { _, _ -> }, {})
        val b = CloudConfigPoller("device-b", "B", true, -1, server::request, { _, _ -> true }, { _, _ -> }, {})
        a.checkOnce()
        b.checkOnce()
        assertFalse(server.requests.contains("GET /devices/device-a/config"))
        assertTrue(server.requests.contains("GET /devices/device-b/config"))
        assertTrue(server.requests.contains("PATCH /devices/device-b"))
        assertFalse(server.requests.any { it == "GET /config" || it == "GET /config/version" })
    }

    @Test
    fun serverResetToLowerPublishedVersionStillRefreshesConfiguration() = runBlocking {
        val server = FakeServer().apply { version = 2 }
        var persisted = -1L
        val poller = CloudConfigPoller("device-a", "A", false, 9, server::request,
            { _, version -> persisted = version; true }, { _, _ -> }, {})
        poller.checkOnce()
        assertEquals(2L, persisted)
    }
}
