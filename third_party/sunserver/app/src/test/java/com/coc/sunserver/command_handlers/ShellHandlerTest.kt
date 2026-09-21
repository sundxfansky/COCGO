package com.coc.sunserver.command_handlers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellHandlerTest {
    @Test fun preservesExitCodeAndOutput() {
        val command = JsonObject().apply { addProperty("command", "printf result; exit 7") }
        val response = JsonParser.parseString(ShellHandler.handle(command)).asJsonObject
        assertEquals(7, response.get("exitCode").asInt)
        assertEquals("result", response.get("data").asString)
    }
    @Test fun silentChildCannotHoldThePipePastItsDeadline() {
        val command = JsonObject().apply { addProperty("command", "sleep 5; printf late") }
        val started = System.nanoTime()
        runCatching { ShellHandler.handle(command, timeoutSeconds = 1) }
        assertTrue("Command waited for its silent child", System.nanoTime() - started < 4_000_000_000L)
    }
}
