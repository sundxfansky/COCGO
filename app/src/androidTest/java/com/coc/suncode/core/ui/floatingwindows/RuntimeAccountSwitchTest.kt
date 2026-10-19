package com.coc.suncode.core.ui.floatingwindows

import android.content.Context
import android.os.Environment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.data.websocket.ServerTransport
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.waitForPlay
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.interfaces.MainCode
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.jar.code.universal.GameVersion
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.smalltools.RuntimeAccountSwitch
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeAccountSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val previousActions = GlobalVars.serverActions
    private val previousPlugin = GlobalVars.pluginUI
    private val previousConfigs = GlobalVars.configStates.toMap()
    private val previousPlaying = GlobalVars.isPlaying.value
    private val previousLoaded = GlobalVars.isConfigLoaded
    private val previousAccount = InGamesVars.currentAccountNumber
    private val previousVersion = InGamesVars.currentGameVersion
    private val previousLogVisibility = ShowMessage.isLogWindowVisible()
    private val previousMode = AppStateManager.currentMode
    private val previousAutoRun = GlobalVars.isAutoRunEnabled
    private val previousSwitching = GlobalVars.isSwitchingAccount
    private val previousInputMethod = GlobalVars.defaultInputMethod
    private var service: ControlWindowService? = null
    private lateinit var transport: FakeRootTransport
    private lateinit var actions: ServerActions

    @Before
    fun prepare() {
        // Keep logic-test diagnostics from creating unrelated transient overlay windows.
        ShowMessage.setLogOverlayVisible(true)
        transport = FakeRootTransport()
        actions = ServerActions(transport, "/runtime-switch-test/")
        GlobalVars.serverActions = actions
        GlobalVars.isConfigLoaded = true
        GlobalVars.isPlaying.value = false
        GlobalVars.isSwitchingAccount = false
        GlobalVars.defaultInputMethod = null
        GlobalVars.configStates.clear()
        setConfig("account_count", "3")
        setConfig("batch_create_account", "0")
        for (number in 1..3) {
            setConfig("game_version$number", "0")
            setConfig("cn_path$number", "save-$number")
        }
        InGamesVars.currentAccountNumber = 2
        InGamesVars.currentGameVersion = GameVersion.CN
        compose.mainClock.autoAdvance = false
    }

    @After
    fun restore() {
        if (service != null) {
            onMain { service?.onDestroy() }
            runBlocking { BotRuntime.stop() }
        }
        AppStateManager.setMode(previousMode)
        GlobalVars.isAutoRunEnabled = previousAutoRun
        GlobalVars.isSwitchingAccount = previousSwitching
        GlobalVars.defaultInputMethod = previousInputMethod
        GlobalVars.serverActions = previousActions
        GlobalVars.pluginUI = previousPlugin
        GlobalVars.configStates.clear()
        GlobalVars.configStates.putAll(previousConfigs)
        GlobalVars.isPlaying.value = previousPlaying
        GlobalVars.isConfigLoaded = previousLoaded
        InGamesVars.currentAccountNumber = previousAccount
        InGamesVars.currentGameVersion = previousVersion
        actions.close()
        ShowMessage.setLogOverlayVisible(previousLogVisibility)
    }

    @Test
    fun exactNeighboursRequireSavesAndNeverSkipAGap() = runBlocking {
        transport.addSave("sunCNGameSave", "save-1")
        assertEquals(AccountSwitchState(previousAccount = 1), RuntimeAccountSwitch.state())
        transport.addSave("sunCNGameSave", "save-3")
        assertEquals(AccountSwitchState(1, 3), RuntimeAccountSwitch.state())
        InGamesVars.currentAccountNumber = 1
        assertEquals(AccountSwitchState(), RuntimeAccountSwitch.state())
        InGamesVars.currentAccountNumber = 3
        assertEquals(AccountSwitchState(), RuntimeAccountSwitch.state())
    }

    @Test
    fun configuredGlobalSaveIsUsedAndSwitchLeavesAutomationPaused() = runBlocking {
        withTimeout(5000) {
            setConfig("game_version3", "1")
            setConfig("global_path3", "global-target")
            transport.addSave("sunGlobalGameSave", "global-target")
            assertTrue(RuntimeAccountSwitch.switchTo(3))
            assertEquals(3, InGamesVars.currentAccountNumber)
            assertEquals(GameVersion.GLOBAL, InGamesVars.currentGameVersion)
            assertEquals("3", transport.memory.get("accountNumber").asString)
            assertFalse(GlobalVars.isPlaying.value)
            assertTrue(transport.commands.any { it == "am start -n ${GameVersion.GLOBAL.launchComponent}" })
            assertTrue(transport.commands.any { it.contains("cp -R") && it.contains("global-target/shared_prefs/.") })
            assertFalse(transport.commands.any { it.contains("databases") })
        }
    }

    @Test
    fun cnRestoreIncludesDatabasesAndPersistsThePreviousAccount() = runBlocking {
        transport.addSave("sunCNGameSave", "save-1")
        assertTrue(RuntimeAccountSwitch.switchTo(1))
        assertEquals("1", transport.memory.get("accountNumber").asString)
        assertTrue(transport.commands.any { it.contains("cp -R") && it.contains("save-1/databases/.") })
        assertFalse(GlobalVars.isPlaying.value)
    }

    @Test
    fun deletedSaveIsRecheckedBeforeStoppingTheGame() = runBlocking {
        transport.addSave("sunCNGameSave", "save-3")
        assertEquals(3, RuntimeAccountSwitch.state().nextAccount)
        transport.directories.clear()
        assertFalse(RuntimeAccountSwitch.switchTo(3))
        assertFalse(transport.commands.any { it.startsWith("am ") || it.startsWith("rm ") })
        assertEquals(2, InGamesVars.currentAccountNumber)
        assertNull(transport.memory.get("accountNumber"))
    }

    @Test
    fun copyFailureDoesNotLaunchOrAdvanceTheAccount() = runBlocking {
        transport.addSave("sunCNGameSave", "save-3")
        transport.failPrefix = "cp -R"
        assertFalse(RuntimeAccountSwitch.switchTo(3))
        assertFalse(transport.commands.any { it.startsWith("am start") })
        assertEquals(2, InGamesVars.currentAccountNumber)
        assertNull(transport.memory.get("accountNumber"))
        assertFalse(GlobalVars.isPlaying.value)
    }

    @Test
    fun incompleteSaveDoesNotClearGameData() = runBlocking {
        transport.addSave("sunCNGameSave", "save-3")
        transport.directories.remove("${transport.sdPath}/sunFiles/sunCNGameSave/save-3/databases")
        assertFalse(RuntimeAccountSwitch.switchTo(3))
        assertFalse(transport.commands.any { it.startsWith("rm ") || it.startsWith("cp ") || it.startsWith("am start") })
    }

    @Test
    fun batchModeUsesNumericGlobalSavesAndRespectsItsBounds() = runBlocking {
        setConfig("batch_create_account", "1")
        setConfig("create_start_id", "10")
        setConfig("create_end_id", "12")
        InGamesVars.currentAccountNumber = 10
        transport.addSave("sunGlobalGameSave", "9")
        transport.addSave("sunGlobalGameSave", "11")
        assertEquals(AccountSwitchState(nextAccount = 11), RuntimeAccountSwitch.state())
        InGamesVars.currentAccountNumber = 12
        transport.addSave("sunGlobalGameSave", "13")
        assertEquals(AccountSwitchState(previousAccount = 11), RuntimeAccountSwitch.state())
    }

    @Test
    fun arrowsOnTheLeftSwitchDirectlyWithoutOpeningThePicker() = verifyToolbar(isAtRightSide = false)

    @Test
    fun arrowsOnTheRightSwitchDirectlyWithoutOpeningThePicker() = verifyToolbar(isAtRightSide = true)

    @Test
    fun switchingDisablesBothArrowsAndPlay() {
        GlobalVars.pluginUI = ToolbarPlugin(AccountSwitchState(1, 3))
        compose.setContent { MaterialTheme { ControlWindow(isSwitchingAccount = true) } }
        compose.onNodeWithContentDescription("Main Icon").performClick()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithContentDescription("上一个账号").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一个账号").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun serviceJoinsTheOldBotRejectsDuplicateTapsAndWaitsForExplicitResume() = runBlocking {
        withTimeout(10_000) {
            val oldScope = CoroutineScope(Dispatchers.Default + Job())
            val oldStarted = CompletableDeferred<Unit>()
            val oldStopped = CompletableDeferred<Unit>()
            val switchStarted = CompletableDeferred<Unit>()
            val finishSwitch = CompletableDeferred<Unit>()
            val botRestarted = CompletableDeferred<Unit>()
            val botResumed = CompletableDeferred<Unit>()
            var switches = 0
            GlobalVars.pluginUI = object : MainCode, RuntimeAccountSwitcher {
                @Composable
                override fun ShowMainUI(context: Context, onClose: () -> Unit) = Unit
                override fun refreshConfigs(actions: ServerActions) = Unit
                override suspend fun accountSwitchState() = AccountSwitchState(1, 3)
                override suspend fun switchToAccount(accountNumber: Int): Boolean {
                    assertTrue(oldStopped.isCompleted)
                    assertFalse(GlobalVars.isPlaying.value)
                    switches++
                    switchStarted.complete(Unit)
                    finishSwitch.await()
                    return true
                }
                override suspend fun runBot() {
                    botRestarted.complete(Unit)
                    waitForPlay()
                    botResumed.complete(Unit)
                }
            }
            try {
                BotRuntime.start(oldScope) {
                    oldStarted.complete(Unit)
                    try { awaitCancellation() } finally { oldStopped.complete(Unit) }
                }
                oldStarted.await()
                startServiceSwitch(3)
                switchStarted.await()
                onMain { service!!.switchToAccount(1) }
                assertEquals(1, switches)
                assertTrue(GlobalVars.isSwitchingAccount)
                assertFalse(GlobalVars.isAutoRunEnabled)
                finishSwitch.complete(Unit)
                botRestarted.await()
                assertFalse(GlobalVars.isPlaying.value)
                assertFalse(GlobalVars.isSwitchingAccount)
                assertFalse(botResumed.isCompleted)
                onMain { GlobalVars.isPlaying.value = true }
                botResumed.await()
            } finally {
                oldScope.cancel()
            }
        }
    }

    @Test
    fun serviceFailureClearsTheBusyFlagAndKeepsAutomationPaused() = runBlocking {
        withTimeout(10_000) {
            val attempted = CompletableDeferred<Unit>()
            GlobalVars.pluginUI = object : MainCode, RuntimeAccountSwitcher {
                @Composable
                override fun ShowMainUI(context: Context, onClose: () -> Unit) = Unit
                override fun refreshConfigs(actions: ServerActions) = Unit
                override suspend fun accountSwitchState() = AccountSwitchState(1, 3)
                override suspend fun switchToAccount(accountNumber: Int): Boolean {
                    attempted.complete(Unit)
                    error("restore failed")
                }
                override suspend fun runBot() = Unit
            }
            startServiceSwitch(3)
            attempted.await()
            while (GlobalVars.isSwitchingAccount) delay(10)
            assertFalse(GlobalVars.isPlaying.value)
            assertFalse(GlobalVars.isAutoRunEnabled)
        }
    }

    private fun startServiceSwitch(account: Int) {
        onMain {
            AppStateManager.setMode(AppMode.Run)
            GlobalVars.isPlaying.value = true
            GlobalVars.isAutoRunEnabled = true
            // Exercise the service coordinator without creating real overlay windows or workers.
            service = ControlWindowService().also {
                (it.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
                it.switchToAccount(account)
            }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun verifyToolbar(isAtRightSide: Boolean) {
        GlobalVars.pluginUI = ToolbarPlugin(AccountSwitchState(nextAccount = 3))
        GlobalVars.isPlaying.value = true
        var selected: Int? = null
        compose.setContent {
            MaterialTheme {
                ControlWindow(isAtRightSide = isAtRightSide, onSwitchAccount = { selected = it })
            }
        }
        compose.onNodeWithContentDescription("Main Icon").performClick()
        compose.mainClock.advanceTimeBy(320)
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            runCatching { compose.onNodeWithContentDescription("下一个账号").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithContentDescription("上一个账号").assertIsNotEnabled().performClick()
        assertNull(selected)
        compose.onNodeWithContentDescription("下一个账号").performClick()
        compose.runOnIdle {
            assertEquals(3, selected)
            assertFalse(GlobalVars.isPlaying.value)
        }
        compose.onNodeWithContentDescription("Switch").assertDoesNotExist()
    }

    private fun setConfig(key: String, value: String) {
        GlobalVars.configStates[key] = mutableStateOf(value)
    }

    private class ToolbarPlugin(private val state: AccountSwitchState) : MainCode, RuntimeAccountSwitcher {
        @Composable
        override fun ShowMainUI(context: Context, onClose: () -> Unit) = Unit
        override fun refreshConfigs(actions: ServerActions) = Unit
        override suspend fun runBot() = Unit
        override suspend fun accountSwitchState() = state
        override suspend fun switchToAccount(accountNumber: Int) = true
    }

    /** Exercise the public host APIs without reading or modifying any real game files. */
    private class FakeRootTransport : ServerTransport {
        val sdPath: String = Environment.getExternalStorageDirectory().path
        val directories = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        var memory = JsonObject()
        var failPrefix: String? = null
        private lateinit var reply: (String) -> Unit

        fun addSave(folder: String, name: String) {
            val root = "$sdPath/sunFiles/$folder/$name"
            directories.addAll(listOf(root, "$root/shared_prefs", "$root/databases"))
        }

        override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            reply = onMessage
            onOpen()
        }

        override fun sendAction(action: Any): Boolean {
            val request = action as Map<*, *>
            val response = JsonObject().apply { addProperty("status", "success") }
            if (request["actionType"] == "shell_action") {
                val command = request["command"] as String
                commands += command
                val exitCode = when {
                    // The host can execute a fail-fast command chain in a single root request.
                    failPrefix?.let { prefix -> command.split(" && ").any { it.startsWith(prefix) } } == true -> 1
                    command.startsWith("[ -d ") -> if (command.substringAfter('"').substringBefore('"') in directories) 0 else 1
                    else -> 0
                }
                response.addProperty("exitCode", exitCode)
                response.addProperty("data", "")
            } else {
                when (request["subAction"]) {
                    "read" -> response.addProperty("data", if (request["path"].toString().endsWith("memory.json")) memory.toString() else "{}")
                    "write" -> {
                        if (request["path"].toString().endsWith("memory.json")) {
                            memory = Gson().fromJson(request["content"].toString(), JsonObject::class.java)
                        }
                        response.addProperty("data", "true")
                    }
                    else -> response.addProperty("data", "true")
                }
            }
            reply(response.toString())
            return true
        }

        override fun close() = Unit
    }
}
