package com.coc.suncode.core.ui.web

import android.content.Context
import android.graphics.Bitmap
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.cloud.CloudSyncManager
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.village.CocVillageExports
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.coc.suncode.core.ui.floatingwindows.RuntimeControls
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.exit.AppExitHelper
import com.coc.suncode.core.util.fileactions.GameSaveTools
import com.coc.suncode.interfaces.FeaturePluginClient
import com.coc.suncode.loadjar.features.FeatureStore
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Optional entry point for the loaded plugin to supply its canonical settings schema. */
interface WebSettingsProvider {
    fun createWebSettingsController(context: Context): LocalSettingsController
}

/** Host actions exposed only to the installed WebView's trusted main frame. */
class WebSettingsSession(
    private val context: Context,
    private val editor: LocalSettingsController,
    private val onClose: () -> Unit
) {
    private val features by lazy { FeatureStore(context.applicationContext) }
    private var closed = false

    suspend fun request(method: String, params: JsonObject): JsonObject = when (method) {
        "getSettingsControls" -> controls()
        "getRuntimeControls" -> RuntimeControls.status()
        "switchRuntimeAccount" -> {
            RuntimeControls.switchAccount(params.string("account").toIntOrNull() ?: 0)
            close(play = false, refresh = false)
        }
        "runSettings" -> close(play = true, refresh = true)
        "pauseSettings" -> close(play = false, refresh = true)
        "closeSettings" -> close(play = false, refresh = false)
        "exitSettings" -> {
            AppExitHelper.exitApplication(context)
            JsonObject()
        }
        "getFeaturePlugins" -> featureState()
        "listVillageExports" -> withContext(Dispatchers.IO) { CocVillageExports.list() }
        "getVillageExport", "publishVillageExport" -> villageExport(params, publish = method == "publishVillageExport")
        "changeFeaturePlugin" -> changeFeature(params)
        "getQqAuthorizationStatus" -> qqStatus(slot(params))
        "startQqAuthorization" -> startQq(params)
        "manageGameSave" -> {
            val region = when (params.string("region")) {
                "0" -> GameSaveTools.Region.CN
                "1" -> GameSaveTools.Region.GLOBAL
                else -> throw IllegalArgumentException("请选择存档版本")
            }
            val suffix = params.string("suffix")
            if (CloudSyncManager.remoteEnabled) {
                val actions = GlobalVars.serverActions
                val configuredRegion = actions?.getValue("extract_version") ?: "0"
                val nameKey = if (configuredRegion == "1") "extract_global" else "extract_cn"
                val configuredName = actions?.getValue(nameKey) ?: "1"
                // The local action follows the published device settings while cloud editing is enabled.
                if (params.string("region") != configuredRegion || suffix != configuredName) {
                    throw SettingsRequestException(409, "存档参数已更新，请刷新设置")
                }
            }
            when (params.string("operation")) {
                "extract" -> GameSaveTools.extract(region, suffix).let { message("提取成功") }
                "delete" -> GameSaveTools.delete(region, suffix).let { message("删除成功") }
                else -> throw IllegalArgumentException("不支持的存档操作")
            }
        }
        "submitFeedback" -> submitFeedback()
        else -> editor.request(method, params)
    }

    private suspend fun villageExport(params: JsonObject, publish: Boolean): JsonObject {
        val slot = CocVillageExports.Slot(params.string("region"), params.string("account").toIntOrNull() ?: 0)
        val snapshot = withContext(Dispatchers.IO) { CocVillageExports.read(slot) }
            ?: throw SettingsRequestException(404, "这个账号还没有游戏导出")
        if (publish) CloudSyncManager.publishVillage(slot, snapshot)
        return snapshot
    }

    private fun controls() = JsonObject().apply {
        addProperty("autoRun", GlobalVars.isAutoRunEnabled)
        addProperty("seconds", GlobalVars.autoRunTimer)
    }

    suspend fun runCountdown() {
        while (GlobalVars.isAutoRunEnabled) {
            delay(1000)
            if (!GlobalVars.isAutoRunEnabled) return
            GlobalVars.autoRunTimer--
            if (GlobalVars.autoRunTimer <= 0) {
                close(play = true, refresh = true)
                return
            }
        }
    }

    fun close(play: Boolean, refresh: Boolean): JsonObject {
        GlobalVars.isAutoRunEnabled = false
        if (refresh) GlobalVars.serverActions?.let { GlobalVars.pluginUI?.refreshConfigs(it) }
        GlobalVars.updateWindowPosition = true
        GlobalVars.isPlaying.value = play
        AppStateManager.setMode(AppMode.Run)
        finish()
        return JsonObject()
    }

    internal fun finish() {
        // Closing the activity can be observed by both the local session and its mode watcher.
        if (closed) return
        closed = true
        onClose()
    }

    private suspend fun featureState() = withContext(Dispatchers.IO) {
        JsonObject().apply {
            add("plugins", JsonArray().apply {
                features.specs.forEach { spec ->
                    add(JsonObject().apply {
                        addProperty("id", spec.id)
                        addProperty("name", spec.name)
                        addProperty("version", features.version(spec.id))
                        addProperty("enabled", features.enabled(spec.id))
                        addProperty("managed", features.cloudOverride(spec.id) != null)
                    })
                }
            })
        }
    }

    private suspend fun changeFeature(params: JsonObject): JsonObject = featureChangeMutex.withLock {
        val spec = features.specs.firstOrNull { it.id == params.string("id") }
            ?: throw IllegalArgumentException("请选择有效的插件")
        val operation = params.string("operation")
        require(operation in setOf("enable", "disable", "install", "restore")) { "不支持的插件操作" }
        if (operation in setOf("enable", "disable") && features.cloudOverride(spec.id) != null) {
            throw SettingsRequestException(403, "插件启停由服务端配置")
        }
        val plugin = GlobalVars.pluginUI as? FeaturePluginClient ?: error("功能插件尚未就绪")
        GlobalVars.isAutoRunEnabled = false
        GlobalVars.isPlaying.value = false
        BotRuntime.stop()
        val result = try {
            when (operation) {
                "enable", "disable" -> withContext(Dispatchers.IO) {
                    features.setEnabled(spec.id, operation == "enable")
                    if (operation == "enable") "${spec.name}已启用" else "${spec.name}已停用"
                }
                "install" -> {
                    val loader = requireNotNull(plugin.javaClass.classLoader)
                    "安装成功，版本 ${features.installUrl(spec.id, params.string("url").trim(), loader)}"
                }
                else -> withContext(Dispatchers.IO) {
                    features.useBundled(spec.id)
                    "已恢复内置插件"
                }
            }
        } finally {
            // Reload committed changes even if the settings window closed during installation.
            withContext(NonCancellable) {
                if (AppStateManager.currentMode != AppMode.Run) BotRuntime.stop()
                (GlobalVars.pluginUI as? FeaturePluginClient)?.reloadFeatures()
            }
        }
        featureState().apply { addProperty("message", result) }
    }

    private suspend fun qqStatus(slot: Int, checkLocal: Boolean = true): JsonObject {
        if (checkLocal) requireLocalEditing()
        val status = QqAuthorizationManager.status.value
        val summary = QqAuthorizationManager.summary(context, slot)
        return JsonObject().apply {
            addProperty("summary", summary)
            addProperty("busy", status.busy)
            addProperty("message", status.message.takeIf { status.accountSlot == slot }.orEmpty())
        }
    }

    private suspend fun startQq(params: JsonObject): JsonObject {
        val slot = slot(params)
        val operation = params.string("operation")
        require(operation == "capture" || operation == "login") { "不支持的 QQ 授权操作" }
        val selection = CloudSyncManager.settingsMutex.withLock {
            requireLocalEditing()
            val actions = checkNotNull(GlobalVars.serverActions) { "本地配置尚未加载" }
            val expectedVersion = params.string("version")
            val savedSlot = actions.getValue("qq_account_slot")?.toIntOrNull() ?: 1
            if (slot != savedSlot) {
                if (editor.settingsVersion() != expectedVersion) {
                    throw SettingsRequestException(409, "本地配置已更新，请刷新后再使用授权工具")
                }
                // Persist the selected reference alone. Other edits stay in the current WebView draft.
                actions.writeToConfigFile("qq_account_slot", slot.toString())
            }
            if (slot != savedSlot) editor.settingsVersion() else null
        }
        val started = if (operation == "capture") QqAuthorizationManager.launch(context, slot)
        else QqAuthorizationManager.launchSaved(context, slot)
        return qqStatus(slot, checkLocal = false).apply {
            if (selection != null) addProperty("version", selection)
            if (!started && get("message").asString.isBlank()) {
                addProperty("message", "无法执行 QQ 授权操作，请查看状态后重试")
            }
        }
    }

    private fun requireLocalEditing() {
        if (CloudSyncManager.remoteEnabled) {
            throw SettingsRequestException(403, "远端同步期间此工具不可用")
        }
    }

    private fun slot(params: JsonObject): Int {
        val slot = params.string("slot").toIntOrNull()
        require(slot != null && slot in 1..10_000) { "请输入 1 至 10000 的授权序号" }
        return slot
    }

    private suspend fun submitFeedback(): JsonObject {
        val baseUrl = BuildConfig.BASE_URL.trimEnd('/')
        check(baseUrl.isNotBlank()) { "宿主未配置反馈服务" }
        return feedbackMutex.withLock {
            try {
                val bitmap = ScreenCaptureManager.captureFeedbackBitmap() ?: error("未能取得截图")
                val bytes = try {
                    withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "截图编码失败" }
                            out.toByteArray()
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                withContext(Dispatchers.IO) {
                    val name = "bug_${System.currentTimeMillis()}.png"
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("image", name, bytes.toRequestBody("image/png".toMediaType())).build()
                    val request = Request.Builder().url("$baseUrl/api/bug-report").post(body).build()
                    feedbackClient.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "截图提交失败 (HTTP ${response.code})" }
                    }
                }
                message("截图提交成功，请通知作者或客服。")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ShowMessage.record("截屏反馈失败：${error.message}", throwable = error)
                throw error
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun message(value: String) = JsonObject().apply { addProperty("message", value) }

    private companion object {
        val featureChangeMutex = Mutex()
        val feedbackMutex = Mutex()
        val feedbackClient = OkHttpClient()
    }
}
