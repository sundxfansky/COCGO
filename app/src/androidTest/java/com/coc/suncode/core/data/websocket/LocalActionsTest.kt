package com.coc.suncode.core.data.websocket

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.checkpermissions.PermissionManager
import com.coc.suncode.core.system.checkpermissions.RootStatus
import com.coc.suncode.core.util.basic.ServerShell
import com.google.gson.JsonObject
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production transport under the authorized app UID, with isolated external files. */
@RunWith(AndroidJUnit4::class)
class LocalActionsTest {
    private fun newDirectory() = "${Environment.getExternalStorageDirectory().path}/sunFiles/.local-test-${UUID.randomUUID()}/"

    @Test
    fun configurationAndLegacyShellWorkWithoutSunserver() = runBlocking {
        withTimeout(30_000) {
            val directory = newDirectory()
            val actions = ServerActions(baseDir = directory)
            val previous = GlobalVars.serverActions
            try {
                assertTrue(actions.awaitReady())
                assertEquals(0, actions.configJson.size())
                val saved = JsonObject().apply { addProperty("local_test", "中文\n引号\"和\\反斜杠🙂") }
                assertTrue(actions.persistConfig(saved))
                assertEquals(saved, actions.configJson)
                val reopened = ServerActions(baseDir = directory)
                try {
                    assertTrue(reopened.awaitReady())
                    assertEquals(saved, reopened.configJson)
                } finally { reopened.close() }

                GlobalVars.serverActions = actions
                val result = ServerShell.exec("printf '中文\\n'; printf 'stderr\\n' >&2; exit 7")
                assertEquals(7, result.code)
                assertFalse(result.isSuccess)
                assertTrue(result.out.contains("中文"))
                assertTrue(result.out.contains("stderr"))
            } finally {
                GlobalVars.serverActions = previous
                delete(actions, directory + "sun_config.json")
                delete(actions, directory)
                actions.close()
            }
        }
    }

    @Test
    fun startupInitializesLocallyAndRetiresLegacyServer() = runBlocking {
        withTimeout(30_000) {
            val previous = GlobalVars.serverActions
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals(RootStatus.GRANTED, PermissionManager.checkAndGrantPermissions(context) {})
                assertNotNull(GlobalVars.serverActions)
                assertTrue(GlobalVars.serverActions!!.awaitReady())
                val processCheck = ServerShell.exec("ps -A -o ARGS | grep '[c]om.coc.sunserver.ShellServer'")
                assertTrue("Legacy sunserver must be stopped: ${processCheck.out}", processCheck.out.all { it.isBlank() })
                val listeners = ServerShell.exec("cat /proc/net/tcp /proc/net/tcp6")
                // 6839 == 0x1AB7. Only a LISTEN state at this local port is forbidden.
                assertFalse(listeners.out.any { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    fields.size > 3 && fields[1].endsWith(":1AB7", ignoreCase = true) && fields[3] == "0A"
                })
            } finally {
                if (GlobalVars.serverActions !== previous) GlobalVars.serverActions?.close()
                GlobalVars.serverActions = previous
            }
        }
    }

    @Test
    fun cancellationStopsShellChildrenAndNextRequestUsesFreshWorker() = runBlocking {
        withTimeout(30_000) {
            val directory = newDirectory()
            val marker = directory + "late.txt"
            val actions = ServerActions(baseDir = directory)
            try {
                assertTrue(actions.awaitReady())
                assertTrue(actions.persistConfig(JsonObject()))
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    actions.sendActionSync(mapOf(
                        "actionType" to "shell_action",
                        "command" to "sleep 2; printf stale > '$marker'"
                    ), timeout = 10_000)
                }
                delay(200)
                pending.cancelAndJoin()
                val response = actions.sendActionSync(mapOf("actionType" to "ping"), timeout = 15_000)
                assertNotNull(response)
                assertEquals("0", response?.get("data")?.asString)
                delay(2200)
                val exists = actions.sendActionSync(mapOf(
                    "actionType" to "file_action", "subAction" to "check_exists", "path" to marker
                ))
                assertEquals("false", exists?.get("data")?.asString)

                // The timeout path must isolate stale replies just like explicit cancellation does.
                assertNull(actions.sendActionSync(mapOf(
                    "actionType" to "shell_action", "command" to "sleep 2; printf delayed"
                ), timeout = 100))
                assertEquals("0", actions.sendActionSync(mapOf("actionType" to "ping"), timeout = 15_000)?.get("data")?.asString)
            } finally {
                delete(actions, marker)
                delete(actions, directory + "sun_config.json")
                delete(actions, directory)
                actions.close()
            }
        }
    }

    private suspend fun delete(actions: ServerActions, path: String) {
        actions.sendActionSync(mapOf("actionType" to "file_action", "subAction" to "delete", "path" to path))
    }
}
