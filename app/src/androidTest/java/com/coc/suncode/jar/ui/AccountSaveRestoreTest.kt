package com.coc.suncode.jar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.data.websocket.ServerTransport
import com.coc.suncode.jar.code.universal.smalltools.writeGameFilesCore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fake the host transport so no archive or game directory on the device is modified. */
@RunWith(AndroidJUnit4::class)
class AccountSaveRestoreTest {
    @Test
    fun failedCopyStopsAndMissingArchiveNeverClearsGameData() = runBlocking {
        val previous = GlobalVars.serverActions
        val transport = FailingCopyTransport()
        val actions = ServerActions(transport, "/test/")
        transport.reply("true")
        transport.reply("{}")
        GlobalVars.serverActions = actions
        try {
            assertFalse(writeGameFilesCore("com.example.game", "1", "fake-archive", listOf("shared_prefs", "databases"), killGame = false))
            assertTrue(transport.commands.last().contains("cp -R"))
            assertTrue(transport.commands.count { it.startsWith("rm ") } == 1)
            transport.commands.clear()
            transport.missing = true
            assertFalse(writeGameFilesCore("com.example.game", "1", "fake-archive", listOf("shared_prefs"), killGame = false))
            assertTrue(transport.commands.none { it.startsWith("rm ") })
        } finally {
            GlobalVars.serverActions = previous
            actions.close()
        }
    }

    private class FailingCopyTransport : ServerTransport {
        private lateinit var receive: (String) -> Unit
        val commands = mutableListOf<String>()
        var missing = false
        override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            receive = onMessage
            onOpen()
        }
        override fun sendAction(action: Any): Boolean {
            val values = action as? Map<*, *> ?: return true
            val command = values["command"] as? String ?: return true
            commands += command
            receive(Gson().toJson(mapOf("status" to "success", "data" to "", "exitCode" to if (missing || command.startsWith("rm ")) 1 else 0)))
            return true
        }
        override fun close() = Unit
        fun reply(data: String) { receive(Gson().toJson(mapOf("status" to "success", "data" to data))) }
    }
}
