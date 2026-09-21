package com.coc.suncode.jar.ui.schema

import android.os.Environment
import com.coc.suncode.core.data.cloud.CloudConfigInbox
import androidx.compose.runtime.mutableStateOf
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager

object ConfigManager {

    /**
     * Initializes all config states from the provided ServerActions.
     */
    fun initializeAllConfigs(actions: ServerActions) {
        val accountCount = actions.getValue("account_count")?.toIntOrNull() ?: 3
        val configCount = actions.getValue("config_count")?.toIntOrNull() ?: 3

        SchemaRegistry.ALL_MODULES.forEach { module ->
            when (module.scope) {
                Scope.GLOBAL -> {
                    module.settings.forEach { def ->
                        val savedValue = actions.getValue(def.key)
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
                            val defaultValue =
                                if (def.key == "global_path" || def.key == "cn_path" || def.key == "data_content") {
                                    i.toString()
                                } else {
                                    def.defaultValue.toString()
                                }
                            GlobalVars.configStates.getOrPut(key) {
                                mutableStateOf(savedValue ?: defaultValue)
                            }.value = savedValue ?: defaultValue
                        }
                    }
                }

                Scope.PROFILE -> {
                    for (i in 1..configCount) {
                        module.settings.forEach { def ->
                            val key = "${def.key}_c$i"
                            val savedValue = actions.getValue(key)
                            GlobalVars.configStates.getOrPut(key) {
                                mutableStateOf(savedValue ?: def.defaultValue.toString())
                            }.value = savedValue ?: def.defaultValue.toString()
                        }
                    }
                }
            }
        }
    }

    fun expandAccountConfigs(newCount: Int) {
        SchemaRegistry.ALL_MODULES.filter { it.scope == Scope.ACCOUNT }.forEach { module ->
            for (i in 1..newCount) {
                module.settings.forEach { def ->
                    val key = "${def.key}${i}"
                    if (!GlobalVars.configStates.containsKey(key)) {
                        val defaultValue =
                            if (def.key == "global_path" || def.key == "cn_path" || def.key == "data_content") {
                                i.toString()
                            } else {
                                def.defaultValue.toString()
                            }
                        GlobalVars.configStates[key] = mutableStateOf(defaultValue)
                    }
                }
            }
        }
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
    suspend fun saveConfigs(onSaveSuccess: () -> Unit = {}) {
        // Require an explicit choice instead of silently overwriting either configuration.
        if (CloudConfigInbox.pending != null) {
            ShowMessage("有新的云端配置，请先选择应用云端或保留本地修改", isChecking = false)
            return
        }
        val baseDir = "${Environment.getExternalStorageDirectory().path}/sunFiles/"
        val accountCountStr = GlobalVars.configStates["account_count"]?.value ?: "3"
        val configCountStr = GlobalVars.configStates["config_count"]?.value ?: "3"

        val accountCount = accountCountStr.toIntOrNull() ?: 3
        val configCount = configCountStr.toIntOrNull() ?: 3

        val saved = SchemaExporter.saveSchemaViaServer(
            baseDir,
            "sun_config.json",
            accountCount = accountCount,
            configCount = configCount
        )

        // Do not leave the settings screen until the root server confirms persistence.
        if (!saved) {
            ShowMessage("配置保存失败，请检查本地服务器连接后重试", isChecking = false)
            return
        }
        // A remote version can arrive while the local write is suspended.
        if (CloudConfigInbox.pending != null) {
            ShowMessage("本地配置已保存，请先处理新到达的云端配置", isChecking = false)
            return
        }
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