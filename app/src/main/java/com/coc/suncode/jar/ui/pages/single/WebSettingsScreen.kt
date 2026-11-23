package com.coc.suncode.jar.ui.pages.single

import android.content.Context
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.web.LocalSettingsController
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.SchemaRegistry
import com.coc.suncode.jar.ui.schema.Scope
import com.coc.suncode.jar.ui.schema.SettingDef
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** The active JAR supplies normalization and canonical labels for the host WebView. */
internal fun createWebSettingsController(context: Context): LocalSettingsController {
    val actions = checkNotNull(GlobalVars.serverActions) { "本地配置尚未加载" }
    return LocalSettingsController(
        context.applicationContext,
        savedSnapshot = { actions.configJson },
        normalize = ::webConfigSnapshot,
        persist = { expected, config ->
            val saved = actions.persistConfigIfUnchanged(expected, webConfigSnapshot(config))
            if (saved == true) {
                // The bundled web adapter may be serving a legacy dynamically loaded runtime.
                GlobalVars.pluginUI?.refreshConfigs(actions) ?: ConfigManager.initializeAllConfigs(actions)
            }
            saved
        },
        schema = ::webSettingsSchema
    )
}

/** Derive editor defaults from saved values only, preserving unknown keys and legacy profile migration. */
internal fun webConfigSnapshot(saved: JsonObject): JsonObject {
    val result = saved.deepCopy()
    val countKey = Schema.GLOBAL_SETTINGS.ACCOUNT_COUNT.key
    val count = saved.get(countKey)?.asString?.toIntOrNull()?.coerceIn(1, 10_000)
        ?: Schema.GLOBAL_SETTINGS.ACCOUNT_COUNT.defaultValue.toString().toInt()
    val independent = saved.get(Schema.GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key)?.asString == "1"
    SchemaRegistry.ALL_MODULES.forEach { module ->
        when (module.scope) {
            Scope.GLOBAL -> module.settings.forEach { def ->
                val value = saved.get(def.key)?.asString
                    ?: if (def.key == Schema.GLOBAL_SETTINGS.AFTER_KICK_COOLDOWN_MINUTES.key) saved.get("after_kick_wait_minutes")?.asString else null
                result.addProperty(def.key, value ?: def.defaultValue.toString())
            }
            Scope.ACCOUNT -> for (index in 1..count) {
                module.settings.forEach { def ->
                    result.addProperty("${def.key}$index", saved.get("${def.key}$index")?.asString ?: webAccountDefault(def, index))
                }
            }
            Scope.PROFILE -> for (index in 1..count) {
                val source = if (independent) index else saved.get("${Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}$index")
                    ?.asString?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                module.settings.forEach { def ->
                    val value = saved.get("${def.key}_c$source")?.asString
                        ?: if (def.key == Schema.BUILDER_BASE_SETTINGS.ENABLED.key) {
                            if (saved.get("${Schema.BUILDER_BASE_SETTINGS.NO_BUILDER_BASE.key}_c$source")?.asString == "1") "0" else "1"
                        } else null
                    result.addProperty("${def.key}_c$index", value ?: def.defaultValue.toString())
                }
            }
        }
    }
    result.addProperty(countKey, count.toString())
    result.addProperty(Schema.GLOBAL_SETTINGS.CONFIG_COUNT.key, count.toString())
    result.addProperty(Schema.GLOBAL_SETTINGS.ACCOUNT_PROFILES_VERSION.key, "1")
    for (index in 1..count) {
        result.addProperty("${Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key}$index", index.toString())
        result.addProperty("${Schema.ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key}$index", index.toString())
    }
    return result
}

private fun webAccountDefault(def: SettingDef, index: Int): String = when (def.key) {
    "global_path", "cn_path", "data_content", Schema.ACCOUNT_SETTINGS.ACCOUNT_CONFIG.key,
    Schema.ACCOUNT_SETTINGS.QQ_AUTHORIZATION_SLOT.key -> index.toString()
    else -> def.defaultValue.toString()
}

/** The active dynamic module owns canonical labels and defaults even after an independent JAR update. */
private fun webSettingsSchema(): JsonObject = JsonObject().apply {
    add("modules", JsonArray().apply {
        SchemaRegistry.ALL_MODULES.forEach { module ->
            add(JsonObject().apply {
                addProperty("name", module.name)
                addProperty("scope", module.scope.name)
                add("settings", JsonArray().apply {
                    module.settings.forEach { def ->
                        add(JsonObject().apply {
                            addProperty("key", def.key)
                            addProperty("displayName", def.displayName)
                            addProperty("defaultValue", def.defaultValue.toString())
                        })
                    }
                })
            })
        }
    })
}
