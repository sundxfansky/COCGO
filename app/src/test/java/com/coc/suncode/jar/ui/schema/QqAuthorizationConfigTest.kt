package com.coc.suncode.jar.ui.schema

import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.data.websocket.ServerTransport
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS
import com.coc.suncode.jar.ui.schema.Schema.GLOBAL_SETTINGS
import com.coc.suncode.jar.ui.schema.Schema.MAIN_BASE_SETTINGS
import com.coc.suncode.jar.ui.schema.Schema.BUILDER_BASE_SETTINGS
import com.coc.suncode.jar.code.universal.InGamesVars
import com.coc.suncode.jar.code.universal.smalltools.getConfigRuntime
import com.coc.suncode.jar.code.mainbase.playMainBase
import com.coc.suncode.jar.code.builderbase.playBuilderBase
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import org.junit.Assert.assertFalse

class QqAuthorizationConfigTest {
    private val originalStates = GlobalVars.configStates.toMap()
    private val connections = mutableListOf<ServerActions>()

    @Before
    fun setUp() { GlobalVars.configStates.clear() }

    @After
    fun tearDown() {
        connections.forEach { it.close() }
        GlobalVars.configStates.clear()
        GlobalVars.configStates.putAll(originalStates)
    }

    private fun load(json: JsonObject, transport: ConfigTransport = ConfigTransport()): ServerActions {
        val actions = ServerActions(transport, "/test/")
        connections += actions
        transport.initialize(json)
        ConfigManager.initializeAllConfigs(actions)
        return actions
    }

    @Test
    fun cooldownMigrationPreservesLocalValueAndPrefersCanonicalValueOnReload() {
        val key = GLOBAL_SETTINGS.AFTER_KICK_COOLDOWN_MINUTES.key
        load(JsonObject().apply { addProperty("after_kick_wait_minutes", "7") })
        assertEquals("7", GlobalVars.configStates[key]!!.value)
        val saved = JsonParser.parseString(SchemaExporter.exportSchemasToJson(accountCount = 3)).asJsonObject
        GlobalVars.configStates.clear()
        load(saved)
        assertEquals("7", GlobalVars.configStates[key]!!.value)

        GlobalVars.configStates.clear()
        load(JsonObject().apply {
            addProperty("after_kick_wait_minutes", "7")
            addProperty(key, "0")
        })
        assertEquals("0", GlobalVars.configStates[key]!!.value)
    }

    @Test
    fun upgradingKeepsExistingAccountToGrantBindings() {
        load(JsonObject().apply { addProperty("account_count", "3") })
        assertEquals(listOf("1", "2", "3"), (1..3).map {
            GlobalVars.configStates["${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}$it"]?.value
        })
    }

    @Test
    fun accountQqSlotsArePinnedToAccountIndexWhileLibrarySelectionSurvivesReload() {
        // Legacy configs that shared or remapped grants are normalized to account N -> grant N on load.
        load(JsonObject().apply {
            addProperty("account_count", "3")
            addProperty("${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}1", "7")
            addProperty("${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}2", "7")
            addProperty("${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}3", "2")
        })
        assertEquals(listOf("1", "2", "3"), (1..3).map {
            GlobalVars.configStates["${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}$it"]?.value
        })
        assertEquals(listOf(1, 2, 3), (1..3).map { ConfigManager.qqAuthorizationSlot(it) })
        GlobalVars.configStates[GLOBAL_SETTINGS.QQ_ACCOUNT_SLOT.key]!!.value = "7"
        val saved = JsonParser.parseString(SchemaExporter.exportSchemasToJson(accountCount = 3)).asJsonObject
        GlobalVars.configStates.clear()
        load(saved)
        assertEquals("7", GlobalVars.configStates[GLOBAL_SETTINGS.QQ_ACCOUNT_SLOT.key]?.value)
        ConfigManager.expandAccountConfigs(4)
        assertEquals("4", GlobalVars.configStates["${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}4"]?.value)
    }

    @Test
    fun syncCopiesMainWorldAndOptionalBuilderBaseToRangeWithoutTouchingAccountSettings() {
        load(JsonObject().apply { addProperty("account_count", "5") })
        fun state(key: String) = GlobalVars.configStates[key]!!
        val gold = MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key
        val builderEnabled = BUILDER_BASE_SETTINGS.ENABLED.key
        state("${gold}_c2").value = "222"
        state("${builderEnabled}_c2").value = "0"
        state("${ACCOUNT_SETTINGS.REMARK.key}4").value = "keep"

        // Main world only: builder base of targets stays untouched; source inside the range is skipped.
        assertEquals(2, ConfigManager.syncAccountProfiles(2, 2..4, includeBuilderBase = false))
        assertEquals(listOf("222", "222", "222"), (2..4).map { state("${gold}_c$it").value })
        assertEquals(MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.defaultValue.toString(), state("${gold}_c5").value)
        assertEquals("1", state("${builderEnabled}_c3").value)
        assertEquals("keep", state("${ACCOUNT_SETTINGS.REMARK.key}4").value)

        // Including builder base copies it too; targets are independent copies afterwards.
        assertEquals(2, ConfigManager.syncAccountProfiles(2, 4..5, includeBuilderBase = true))
        assertEquals(listOf("0", "0"), (4..5).map { state("${builderEnabled}_c$it").value })
        state("${gold}_c5").value = "555"
        assertEquals("222", state("${gold}_c4").value)
        assertEquals(0, ConfigManager.syncAccountProfiles(2, 2..2, includeBuilderBase = true))
    }

    @Test
    fun legacySwappedAndSharedProfilesBecomeIndependentAccountCopies() {
        load(JsonObject().apply {
            addProperty("account_count", "3")
            addProperty("config_count", "2")
            addProperty("account_config1", "2")
            addProperty("account_config2", "1")
            addProperty("account_config3", "2")
            addProperty("${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c1", "100")
            addProperty("${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c2", "200")
            addProperty("${BUILDER_BASE_SETTINGS.NO_BUILDER_BASE.key}_c2", "1")
        })
        fun gold(index: Int) = GlobalVars.configStates["${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c$index"]!!
        assertEquals(listOf("200", "100", "200"), (1..3).map { gold(it).value })
        assertEquals(listOf("0", "1", "0"), (1..3).map {
            GlobalVars.configStates["${BUILDER_BASE_SETTINGS.ENABLED.key}_c$it"]!!.value
        })
        gold(1).value = "999"
        assertEquals("200", gold(3).value)
        assertEquals(listOf("1", "2", "3"), (1..3).map {
            GlobalVars.configStates["${ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}$it"]!!.value
        })
        val saved = JsonParser.parseString(SchemaExporter.exportSchemasToJson(accountCount = 3, configCount = 3)).asJsonObject
        // Even stale legacy bindings must not remap profiles after a migrated save is reloaded.
        saved.addProperty("account_config1", "2")
        GlobalVars.configStates.clear()
        load(saved)
        assertEquals(listOf("999", "100", "200"), (1..3).map { gold(it).value })
        assertEquals("1", GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key]!!.value)
    }

    @Test
    fun accountExpansionCreatesWorldSettingsWithoutChangingExistingAccounts() {
        load(JsonObject().apply { addProperty("account_count", "1") })
        val key = MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key
        GlobalVars.configStates["${key}_c1"]!!.value = "123"
        ConfigManager.expandAccountConfigs(3)
        assertEquals("123", GlobalVars.configStates["${key}_c1"]!!.value)
        assertEquals(MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.defaultValue.toString(), GlobalVars.configStates["${key}_c3"]!!.value)
        assertEquals("1", GlobalVars.configStates["${MAIN_BASE_SETTINGS.ENABLED.key}_c3"]!!.value)
        assertEquals("3", GlobalVars.configStates[GLOBAL_SETTINGS.CONFIG_COUNT.key]!!.value)
    }

    @Test
    fun runtimeUsesCurrentAccountAndDisabledWorldsReturnBeforeAnyDeviceWork() = runBlocking {
        val originalAccount = InGamesVars.currentAccountNumber
        try {
            load(JsonObject().apply {
                addProperty("account_count", "2")
                addProperty(GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key, "1")
                addProperty(GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key, "0")
                addProperty("${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c1", "100")
                addProperty("${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c2", "200")
                addProperty("${MAIN_BASE_SETTINGS.ENABLED.key}_c2", "0")
                addProperty("${BUILDER_BASE_SETTINGS.ENABLED.key}_c2", "0")
            })
            InGamesVars.currentAccountNumber = 2
            GlobalVars.configStates["${ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}2"]!!.value = "1"
            assertEquals("200", getConfigRuntime(MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key))
            // No root transport is installed: reaching a device action would fail this test.
            assertTrue(playMainBase())
            assertTrue(playBuilderBase())
            GlobalVars.configStates[GLOBAL_SETTINGS.BATCH_CREATE_ACCOUNT.key]!!.value = "1"
            assertEquals("100", getConfigRuntime(MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key))
        } finally { InGamesVars.currentAccountNumber = originalAccount }
    }

    @Test
    fun detailSaveAndImmediateQqReferenceWriteKeepMigratedProfilesWithoutLeavingSettings() = runBlocking {
        val previous = GlobalVars.serverActions
        val mode = AppStateManager.currentMode
        val transport = ConfigTransport()
        val actions = load(JsonObject().apply { addProperty("account_count", "3") }, transport)
        GlobalVars.serverActions = actions
        AppStateManager.setMode(AppMode.Main)
        try {
            val profileKey = "${MAIN_BASE_SETTINGS.GOLD_REQUIREMENT.key}_c3"
            GlobalVars.configStates[profileKey]!!.value = "333"
            val failed = async(start = CoroutineStart.UNDISPATCHED) { ConfigManager.persistConfigs() }
            transport.reply("", success = false)
            assertFalse(failed.await())
            assertEquals(AppMode.Main, AppStateManager.currentMode)
            val saved = async(start = CoroutineStart.UNDISPATCHED) { ConfigManager.persistConfigs() }
            val referenceKey = "${ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}1"
            val reference = async(start = CoroutineStart.UNDISPATCHED) { actions.writeToConfigFile(referenceKey, "8") }
            transport.reply("20")
            assertTrue(saved.await())
            // The queued reference update must start from the newly saved account snapshot.
            val payload = JsonParser.parseString((transport.sent.last() as Map<*, *>)["content"] as String).asJsonObject
            assertEquals("333", payload[profileKey].asString)
            assertEquals("1", payload[GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key].asString)
            assertEquals("8", payload[referenceKey].asString)
            transport.reply("20")
            reference.await()
            assertEquals(AppMode.Main, AppStateManager.currentMode)
        } finally {
            GlobalVars.serverActions = previous
            AppStateManager.setMode(mode)
        }
    }

    /** Reconstruct configuration through the real host initialization protocol without filesystem I/O. */
    private class ConfigTransport : ServerTransport {
        private lateinit var receive: (String) -> Unit
        val sent = mutableListOf<Any>()
        override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            receive = onMessage
            onOpen()
        }
        override fun sendAction(action: Any): Boolean { sent += action; return true }
        override fun close() = Unit
        fun reply(data: String, success: Boolean = true) {
            receive(Gson().toJson(mapOf("status" to if (success) "success" else "error", "data" to data)))
        }
        fun initialize(json: JsonObject) {
            receive(Gson().toJson(mapOf("status" to "success", "data" to "true")))
            receive(Gson().toJson(mapOf("status" to "success", "data" to json.toString())))
        }
    }
}
