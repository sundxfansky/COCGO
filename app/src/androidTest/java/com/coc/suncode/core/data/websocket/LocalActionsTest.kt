package com.coc.suncode.core.data.websocket

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import com.coc.suncode.core.system.accessibility.MyAccessibilityService
import com.coc.suncode.core.system.inputmethod.SunInputMethodService
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
    fun rootOperationsUseSystemMountNamespace() = runBlocking {
        val directory = newDirectory()
        val actions = ServerActions(baseDir = directory)
        try {
            assertTrue(actions.awaitReady())
            // A root UID inside an app's mount namespace can still modify only shadow package data.
            val response = actions.sendActionSync(mapOf(
                "actionType" to "shell_action",
                "command" to "readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt"
            ))
            assertEquals("success", response?.get("status")?.asString)
            assertEquals(0, response?.get("exitCode")?.asInt)
            val namespaces = response!!.get("data").asString.trim().lines()
            assertEquals(2, namespaces.size)
            assertEquals(namespaces[1], namespaces[0])
        } finally {
            delete(actions, directory + "sun_config.json")
            delete(actions, directory)
            actions.close()
        }
    }

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
                val previousServices = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty().split(':').filter { it.isNotBlank() }
                val previousKeyboard = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                )
                assertEquals(RootStatus.GRANTED, PermissionManager.checkAndGrantPermissions(context) {})
                assertTrue(Settings.canDrawOverlays(context))
                assertTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertTrue(Environment.isExternalStorageManager())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                }
                val power = context.getSystemService(PowerManager::class.java)
                assertTrue(power.isIgnoringBatteryOptimizations(context.packageName))
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).split(':')
                assertTrue(enabledServices.containsAll(previousServices))
                assertTrue(enabledServices.any {
                    ComponentName.unflattenFromString(it) == ComponentName(context, MyAccessibilityService::class.java)
                })
                assertEquals(previousKeyboard, Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                ))
                val enabledImes = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS
                ).split(':')
                assertTrue(enabledImes.any {
                    ComponentName.unflattenFromString(it.substringBefore(';')) == ComponentName(context, SunInputMethodService::class.java)
                })
                val user = Process.myUid() / 100_000
                val projection = ServerShell.exec("cmd appops get --user $user ${context.packageName} PROJECT_MEDIA")
                assertTrue(projection.isSuccess)
                assertTrue(projection.out.any { it.trim().startsWith("PROJECT_MEDIA: allow") })
                // Repeated grants must leave the same backend alive and preserve enabled services.
                val readyActions = GlobalVars.serverActions
                assertEquals(RootStatus.GRANTED, PermissionManager.checkAndGrantPermissions(context) {})
                assertTrue(readyActions === GlobalVars.serverActions)
                assertEquals(enabledServices, Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).split(':'))
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
