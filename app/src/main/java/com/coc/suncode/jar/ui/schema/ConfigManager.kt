package com.coc.suncode.jar.ui.schema

import com.coc.suncode.core.data.cloud.CloudConfigInbox
import androidx.compose.runtime.mutableStateOf
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonParser

object ConfigManager {

    /**
     * Initializes all config states from the provided ServerActions.
     */
    fun initializeAllConfigs(actions: ServerActions) {
        val accountCount = actions.getValue("account_count")?.toIntOrNull() ?: 3
        val independent = actions.getValue(Schema.GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key) == "1"

        SchemaRegistry.ALL_MODULES.forEach { module ->
            when (module.scope) {
                Scope.GLOBAL -> {
                    module.settings.forEach { def ->
                        // Migrate the local cooldown setting while preferring an explicit canonical value.
                        val savedValue = actions.getValue(def.key)
                            ?: if (def.key == Schema.GLOBAL_SETTINGS.AFTER_KICK_COOLDOWN_MINUTES.key) {
                                actions.getValue("after_kick_wait_minutes")
                            } else null
                        GlobalVars.configStates.getOrPut(def.key) {
                            mutableStateOf(savedValue ?: def.defaultValue.toString())
                        }.value = savedValue ?: def.defaultValue.toString()
                    }
                }

                Scope.ACCOUNT -> {
                    for (i in 1..accountCount) {
                        module.settings.forEach { def ->
                            val key = "${def.key}${i}"
                            val savedValue = actions.getValue(key)
                            val defaultValue = accountDefault(def, i)
                            GlobalVars.configStates.getOrPut(key) {
                                mutableStateOf(savedValue ?: defaultValue)
                            }.value = savedValue ?: defaultValue
                        }
                    }
                }

                Scope.PROFILE -> {
                    for (i in 1..accountCount) {
                        // Read from the immutable saved snapshot before publishing each account's own copy.
                        val source = if (independent) i else actions.getValue("${Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}$i")
                            ?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                        module.settings.forEach { def ->
                            val key = "${def.key}_c$i"
                            val savedValue = actions.getValue("${def.key}_c$source")
                                ?: if (def.key == Schema.BUILDER_BASE_SETTINGS.ENABLED.key) {
                                    // Convert the legacy negative toggle into the positive world master switch.
                                    if (actions.getValue("${Schema.BUILDER_BASE_SETTINGS.NO_BUILDER_BASE.key}_c$source") == "1") "0" else "1"
                                } else null
                            GlobalVars.configStates.getOrPut(key) {
                                mutableStateOf(savedValue ?: def.defaultValue.toString())
                            }.value = savedValue ?: def.defaultValue.toString()
                        }
                    }
                }
            }
        }
        GlobalVars.configStates[Schema.GLOBAL_SETTINGS.CONFIG_COUNT.key]?.value = accountCount.toString()
        GlobalVars.configStates[Schema.GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key]?.value = "1"
        for (i in 1..accountCount) {
            GlobalVars.configStates["${Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}$i"]?.value = i.toString()
        }
    }

    fun expandAccountConfigs(newCount: Int) {
        if (newCount !in 1..10_000) return
        SchemaRegistry.ALL_MODULES.filter { it.scope == Scope.ACCOUNT }.forEach { module ->
            for (i in 1..newCount) {
                module.settings.forEach { def ->
                    val key = "${def.key}${i}"
                    if (!GlobalVars.configStates.containsKey(key)) {
                        val defaultValue = accountDefault(def, i)
                        GlobalVars.configStates[key] = mutableStateOf(defaultValue)
                    }
                }
            }
        }
        expandProfileConfigs(newCount)
        GlobalVars.configStates[Schema.GLOBAL_SETTINGS.CONFIG_COUNT.key]?.value = newCount.toString()
    }

    private fun accountDefault(def: SettingDef, index: Int): String = when (def.key) {
        // Preserve existing numbered grants when upgrading from account-bound authorization.
        "global_path", "cn_path", "data_content", Schema.ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key,
        Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key -> index.toString()
        else -> def.defaultValue.toString()
    }

    fun expandProfileConfigs(newCount: Int) {
        SchemaRegistry.ALL_MODULES.filter { it.scope == Scope.PROFILE }.forEach { module ->
            for (i in 1..newCount) {
                module.settings.forEach { def ->
                    val key = "${def.key}_c$i"
                    if (!GlobalVars.configStates.containsKey(key)) {
                        GlobalVars.configStates[key] = mutableStateOf(def.defaultValue.toString())
                    }
                }
            }
        }
    }

    /**
     * Saves all configs to the JSON file via the server.
     */
    suspend fun persistConfigs(): Boolean {
        // Require an explicit choice instead of silently overwriting either configuration.
        if (CloudConfigInbox.pending != null) {
            ShowMessage("有新的云端配置，请先选择应用云端或保留本地修改", isChecking = false)
            return false
        }
        val accountCountStr = GlobalVars.configStates["account_count"]?.value ?: "3"
        val accountCount = accountCountStr.toIntOrNull() ?: 3
        val configCount = accountCount

        val snapshot = JsonParser.parseString(
            SchemaExporter.exportSchemasToJson(accountCount = accountCount, configCount = configCount)
        ).asJsonObject
        // Share the host configuration lock with immediate QQ selector writes to prevent stale overwrites.
        val saved = GlobalVars.serverActions?.persistConfig(snapshot) == true

        // Do not leave the settings screen until the root server confirms persistence.
        if (!saved) {
            ShowMessage("配置保存失败，请检查本地服务器连接后重试", isChecking = false)
            return false
        }
        // A remote version can arrive while the local write is suspended.
        if (CloudConfigInbox.pending != null) {
            ShowMessage("本地配置已保存，请先处理新到达的云端配置", isChecking = false)
            return false
        }
        return true
    }

    suspend fun saveConfigs(onSaveSuccess: () -> Unit = {}) {
        if (!persistConfigs()) return
        GlobalVars.updateWindowPosition = true
        AppStateManager.setMode(AppMode.Run)
        onSaveSuccess()//in here, the UI config window is closed, and control window will be shown.
    }

    /**
     * Save configs and run the bot.
     */
    suspend fun saveAndRun(onSaveSuccess: () -> Unit = {}) {
        saveConfigs(onSaveSuccess)
    }
}
