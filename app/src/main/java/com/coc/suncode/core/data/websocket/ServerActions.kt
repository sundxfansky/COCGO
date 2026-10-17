package com.coc.suncode.core.data.websocket

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.InitConfigs
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

// Keep this public type and package stable for already-built dynamic plugins.
class ServerActions(
    private val serverConnection: ServerTransport = LocalActionTransport(),
    private val baseDir: String = "${Environment.getExternalStorageDirectory().path}/sunFiles/",
    private val onConfigLoaded: (() -> Unit)? = null
) {
    private val gson = Gson()
    var configJson by mutableStateOf(JsonObject())
        private set
    var isLoading by mutableStateOf(true)
        private set

    private val actionMutex = Mutex()
    private val configMutex = Mutex()
    private val stateLock = Any()
    private var actionDeferred: CompletableDeferred<JsonObject>? = null
    private var startup = CompletableDeferred<Unit>()
    private var generation = 0L
    private var closed = false
    private val configPath = "${baseDir}sun_config.json"

    init { performConnect() }

    fun getValue(key: String): String? = configJson.get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString

    private fun performConnect() = synchronized(stateLock) {
        if (closed) return@synchronized
        val current = ++generation
        val ready = CompletableDeferred<Unit>()
        startup = ready
        isLoading = true
        var startupResponses = 0
        serverConnection.connect(
            onOpen = {
                synchronized(stateLock) {
                    if (current == generation && !closed) {
                        // Keep both startup requests pipelined; their responses precede normal actions.
                        InitConfigs.sendStartupRequests(serverConnection, baseDir, configPath)
                    }
                }
            },
            onMessage = { message ->
                synchronized(stateLock) {
                    if (current == generation && !closed) {
                        try {
                            val response = gson.fromJson(message, JsonObject::class.java)
                                ?: throw IOException("Empty local response")
                            if (startupResponses < 2) {
                                startupResponses++
                                // The first reply checks the directory; only the second reads config.
                                if (startupResponses == 2) {
                                    val result = InitConfigs.handleStartupResponse(response, gson)
                                    if (!result.isLoaded) throw IOException(
                                        response.get("message")?.asString ?: "本地配置格式无效，未覆盖原文件"
                                    )
                                    result.configJson?.let { configJson = it }
                                    isLoading = false
                                    ready.complete(Unit)
                                    if (result.shouldCallback) onConfigLoaded?.invoke()
                                }
                            } else {
                                actionDeferred?.complete(response)
                            }
                        } catch (e: Exception) {
                            isLoading = false
                            ready.completeExceptionally(e)
                            actionDeferred?.completeExceptionally(e)
                            ShowMessage("读取本地数据失败: ${e.message}", isChecking = false)
                        }
                    }
                }
            },
            onFailure = { error ->
                synchronized(stateLock) {
                    if (current == generation && !closed) {
                        isLoading = false
                        ready.completeExceptionally(error)
                        actionDeferred?.completeExceptionally(error)
                    }
                }
            }
        )
    }

    /** Do not expose an empty configuration when root startup or an existing file cannot be read. */
    suspend fun awaitReady(timeout: Long = 15_000L): Boolean = try {
        withTimeoutOrNull(timeout) {
            val ready = synchronized(stateLock) {
                check(!closed) { "ServerActions is closed" }
                startup
            }
            ready.await()
            true
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ShowMessage("本地配置初始化失败: ${error.message}", isChecking = false)
        false
    }

    suspend fun writeToConfigFile(key: String, content: String) {
        configMutex.withLock {
            val updated = configJson.deepCopy().apply { addProperty(key, content) }
            check(persistConfigLocked(updated)) { "配置写入失败" }
        }
    }

    /** Publish new configuration only after the internal root worker atomically persists it. */
    suspend fun persistConfig(newJson: JsonObject): Boolean = configMutex.withLock { persistConfigLocked(newJson) }

    private suspend fun persistConfigLocked(newJson: JsonObject): Boolean {
        val snapshot = newJson.deepCopy()
        val response = sendActionSync(mapOf(
            "actionType" to "file_action", "subAction" to "write",
            "path" to configPath, "content" to gson.toJson(snapshot)
        ))
        if (response?.get("status")?.asString != "success") return false
        updateConfig(snapshot)
        return true
    }

    suspend fun sendActionSync(action: Any, timeout: Long = 5000L): JsonObject? = actionMutex.withLock {
        val deferred = CompletableDeferred<JsonObject>()
        var sent = false
        try {
            withTimeoutOrNull(timeout) {
                val ready = synchronized(stateLock) {
                    check(!closed) { "ServerActions is closed" }
                    startup
                }
                ready.await()
                synchronized(stateLock) {
                    check(!closed) { "ServerActions is closed" }
                    actionDeferred = deferred
                    sent = serverConnection.sendAction(action)
                    if (!sent) throw IOException("Local command send failed")
                }
                deferred.await()
            }.also { response ->
                if (response == null) {
                    reconnect()
                    ShowMessage("本地文件或命令请求超时", isChecking = false)
                }
            }
        } catch (e: CancellationException) {
            // A cancelled in-flight request must not donate its late reply to the next caller.
            if (sent) reconnect()
            throw e
        } catch (e: Exception) {
            reconnect()
            ShowMessage("本地文件或命令请求失败: ${e.message}", isChecking = false)
            null
        } finally {
            synchronized(stateLock) {
                if (actionDeferred === deferred) actionDeferred = null
            }
        }
    }

    private fun reconnect() = synchronized(stateLock) {
        if (!closed) {
            serverConnection.close()
            performConnect()
        }
    }

    fun updateConfig(newJson: JsonObject) { configJson = newJson.deepCopy() }

    fun close() = synchronized(stateLock) {
        closed = true
        generation++
        val error = IOException("ServerActions closed")
        startup.completeExceptionally(error)
        actionDeferred?.completeExceptionally(error)
        isLoading = false
        serverConnection.close()
    }
}
