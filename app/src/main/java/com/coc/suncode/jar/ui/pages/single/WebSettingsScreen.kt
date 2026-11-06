package com.coc.suncode.jar.ui.pages.single

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coc.suncode.core.data.cloud.CloudSyncManager
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.web.LocalSettingsController
import com.coc.suncode.core.ui.web.SettingsWebView
import com.coc.suncode.core.util.exit.AppExitHelper
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.SchemaRegistry
import com.coc.suncode.jar.ui.schema.Scope
import com.coc.suncode.jar.ui.schema.SettingDef
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.delay

/** All ordinary settings share the same bundled web UI as the server; device operations stay native. */
@Composable
fun WebSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val actions = GlobalVars.serverActions ?: return
    var toolsVisible by remember { mutableStateOf(false) }
    val controller = remember(actions) {
        LocalSettingsController(
            context.applicationContext,
            savedSnapshot = { actions.configJson },
            normalize = ::webConfigSnapshot,
            persist = { expected, config ->
                val saved = actions.persistConfigIfUnchanged(expected, webConfigSnapshot(config))
                if (saved == true) ConfigManager.initializeAllConfigs(actions)
                saved
            },
            schema = ::webSettingsSchema
        )
    }
    val useSavedSettings: (Boolean) -> Unit = { play ->
        // Browser drafts are committed by the page's Save button; native actions use the last saved revision.
        ConfigManager.initializeAllConfigs(actions)
        GlobalVars.isAutoRunEnabled = false
        GlobalVars.updateWindowPosition = true
        GlobalVars.isPlaying.value = play
        AppStateManager.setMode(AppMode.Run)
        onClose()
    }
    LaunchedEffect(actions) { CloudSyncManager.ensureStarted(context) }
    LaunchedEffect(GlobalVars.isAutoRunEnabled, GlobalVars.autoRunTimer) {
        if (GlobalVars.isAutoRunEnabled) {
            delay(1000)
            GlobalVars.autoRunTimer--
            if (GlobalVars.autoRunTimer <= 0) useSavedSettings(true)
        }
    }
    BackHandler(enabled = toolsVisible) { toolsVisible = false }
    Box(Modifier.fillMaxSize()) {
        // Keep the WebView mounted underneath tools so an unsaved browser draft survives a round trip.
        Column(Modifier.fillMaxSize().background(Color(0xFFF4F6FB))) {
            SettingsWebView(controller, Modifier.weight(1f).fillMaxWidth(), onInteraction = { GlobalVars.isAutoRunEnabled = false })
            if (GlobalVars.isAutoRunEnabled) {
                Text("${GlobalVars.autoRunTimer} 秒后运行已保存配置，触摸任意位置停止", modifier = Modifier.padding(horizontal = 10.dp))
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                CustomButton(text = "运行已保存配置", onClick = { useSavedSettings(true) })
                CustomButton(text = "暂停", onClick = { useSavedSettings(false) })
                CustomButton(text = "设备工具", onClick = { GlobalVars.isAutoRunEnabled = false; toolsVisible = true })
                CustomButton(text = "退出", onClick = { AppExitHelper.exitApplication(context) })
            }
        }
        if (toolsVisible) NativeDeviceTools(onBack = { toolsVisible = false }, onClose = onClose)
    }
}

@Composable
private fun NativeDeviceTools(onBack: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFFF4F6FB))) {
        CustomButton(text = "返回设置", onClick = onBack)
        // Existing device-only utilities remain native and are never exported to the web bridge.
        LazyColumn(Modifier.weight(1f).padding(4.dp)) {
            if (!CloudSyncManager.remoteEnabled) item { QqAuthorizationSettings() }
            item { FeaturePluginsSettings() }
            if (!CloudSyncManager.remoteEnabled) item { ExtractGameSaveContent() }
            item {
                CustomButton(text = "截屏反馈问题", onClick = {
                    AppStateManager.setMode(AppMode.BugReport)
                    onClose()
                })
            }
        }
    }
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
