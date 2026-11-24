package com.coc.suncode.core.data.cloud

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.village.CocVillageExports
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Process-owned sync remains active while the floating services outlive MainActivity. */
object CloudSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sync: CloudConfigSync? = null
    private var generation = 0L

    // Connection changes, local saves and remote commits share this gate to prevent stale writers.
    val settingsMutex = Mutex()
    var remoteEnabled by mutableStateOf(false)
        private set
    var status by mutableStateOf("local")
        private set
    var message by mutableStateOf("")
        private set
    var remoteVersion by mutableStateOf<Long?>(null)
        private set
    var lastCheckedAt by mutableStateOf(0L)
        private set

    fun ensureStarted(context: Context) { if (sync == null) restart(context) }

    internal suspend fun publishVillage(slot: CocVillageExports.Slot, snapshot: JsonObject) {
        val connection = sync ?: throw IllegalStateException("请先在主页设置中启用远端同步")
        connection.publishVillage(slot, snapshot)
    }

    fun stop() {
        generation++
        sync?.stop()
        sync = null
        CloudConfigInbox.clear()
    }

    fun connection(context: Context): JsonObject {
        val preferences = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val endpoint = preferences.getString("base_url", defaultEndpoint()).orEmpty()
        val tokenSet = !preferences.getString("token", "").isNullOrBlank()
        return JsonObject().apply {
            addProperty("endpoint", endpoint)
            addProperty("enabled", preferences.getBoolean("enabled", tokenSet && endpoint.isNotBlank()))
            addProperty("tokenSet", tokenSet)
            addProperty("deviceId", deviceId(context))
            addProperty("deviceName", preferences.getString("device_name", defaultDeviceName()).orEmpty())
        }
    }

    /** The caller holds settingsMutex; credentials never appear in a bridge response. */
    fun configure(context: Context, endpoint: String, enabled: Boolean, token: String?, deviceName: String?) {
        val normalized = CloudConfigPolicy.endpoint(endpoint)
        val preferences = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val oldEndpoint = runCatching { CloudConfigPolicy.endpoint(preferences.getString("base_url", defaultEndpoint()).orEmpty()) }.getOrDefault("")
        val supplied = token.orEmpty().trim()
        val saved = preferences.getString("token", "").orEmpty()
        val nextToken = supplied.ifBlank { if (normalized == oldEndpoint) saved else "" }
        require(!enabled || (normalized.isNotBlank() && nextToken.isNotBlank())) { "启用远端同步前请填写服务端地址和访问 Token；更换地址需重新填写 Token" }
        val name = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: preferences.getString("device_name", defaultDeviceName()).orEmpty()
        require(name.codePointCount(0, name.length) <= 80 && name.none { it.isISOControl() }) { "设备名称不能超过 80 个字符或包含控制字符" }
        val renameDevice = name != preferences.getString("device_name", defaultDeviceName()).orEmpty()
        preferences.edit().putString("base_url", normalized).putString("token", nextToken).putString("device_name", name)
            .putBoolean("device_name_dirty", renameDevice || preferences.getBoolean("device_name_dirty", false))
            .putBoolean("enabled", enabled).apply()
        restart(context)
    }

    fun restart(context: Context) {
        stop()
        val application = context.applicationContext
        val preferences = application.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val endpoint = runCatching { CloudConfigPolicy.endpoint(preferences.getString("base_url", defaultEndpoint()).orEmpty()) }.getOrDefault("")
        val token = preferences.getString("token", "").orEmpty()
        remoteEnabled = preferences.getBoolean("enabled", endpoint.isNotBlank() && token.isNotBlank())
        remoteVersion = null
        lastCheckedAt = 0L
        message = ""
        status = if (remoteEnabled) "connecting" else "local"
        if (!remoteEnabled) return
        if (endpoint.isBlank() || token.isBlank()) {
            status = "error"
            message = "请补充服务端地址和访问 Token"
            return
        }
        val actions = GlobalVars.serverActions ?: return
        val current = generation
        sync = CloudConfigSync(
            application,
            endpoint, token, deviceId(application), preferences.getString("device_name", defaultDeviceName()).orEmpty(),
            preferences.getBoolean("device_name_dirty", false),
            actions, application.getSharedPreferences("cloud_versions", Context.MODE_PRIVATE),
            onRegisteredName = { name ->
                if (current == generation) preferences.edit().putString("device_name", name).putBoolean("device_name_dirty", false).apply()
            },
            applyConfig = { config, version ->
                settingsMutex.withLock {
                    if (current != generation || !remoteEnabled) return@withLock false
                    if (!actions.persistConfig(config)) return@withLock false
                    GlobalVars.pluginUI?.refreshConfigs(actions)
                    remoteVersion = version
                    true
                }
            },
            onStatus = { state, detail, version ->
                if (current == generation) {
                    status = state
                    message = detail
                    if (version != null) remoteVersion = version
                    lastCheckedAt = System.currentTimeMillis()
                }
            }
        ).also { it.start(scope) }
    }

    private fun defaultEndpoint(): String = BuildConfig.BASE_URL.takeIf { it.isNotBlank() }
        ?.trimEnd('/')?.plus("/api/v1").orEmpty()

    /** The identifier stays private to this installation and is never selected by a web page. */
    private fun deviceId(context: Context): String {
        val preferences = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val saved = preferences.getString("device_id", "").orEmpty()
        if (runCatching { UUID.fromString(saved) }.isSuccess) return saved
        return UUID.randomUUID().toString().also { preferences.edit().putString("device_id", it).apply() }
    }

    private fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifBlank { "Android 设备" }
}
