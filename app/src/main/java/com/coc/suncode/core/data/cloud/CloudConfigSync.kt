package com.coc.suncode.core.data.cloud

import android.content.SharedPreferences
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Polls the cloud config endpoint and forwards newer JSON to the local config server. */
class CloudConfigSync(
    private val endpoint: String,
    private val token: String,
    private val actions: ServerActions,
    private val versions: SharedPreferences,
    private val onConfigUpdated: (() -> Unit)? = null
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (endpoint.isBlank() || token.isBlank() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val versionKey = MessageDigest.getInstance("SHA-256").digest("$endpoint:$token".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val version = AtomicInteger(versions.getInt(versionKey, 0))
            fun acknowledge(next: Int) { versions.edit().putInt(versionKey, next).apply() }
            while (isActive) {
                try {
                    val request = Request.Builder().url(endpoint.trimEnd('/') + "/config")
                        .header("Authorization", "Bearer $token").header("If-None-Match", version.get().toString()).build()
                    client.newCall(request).awaitResponse().use { response ->
                        if (response.code == 401) {
                            ShowMessage("云端会话已到期或注销，请重新登录并更新 Token", isChecking = false)
                            return@launch
                        }
                        if (response.code == 200) {
                            val body = response.body.string()
                            val root = gson.fromJson(body, JsonObject::class.java)
                            val next = root.get("version")?.asInt ?: version.get()
                            if (next > version.get() && root.has("config")) {
                                val config = root.getAsJsonObject("config")
                                withContext(Dispatchers.Main.immediate) {
                                    // A pending remote version is explicit and never overwrites a settings draft.
                                    val pending = CloudConfigInbox.Pending(next, config, actions) {
                                        acknowledge(next)
                                        version.set(next)
                                    }
                                    if (AppStateManager.currentMode != AppMode.Run || !GlobalVars.isConfigLoaded || CloudConfigInbox.pending != null) {
                                        CloudConfigInbox.offer(pending)
                                    } else if (actions.persistConfig(config)) {
                                        if (AppStateManager.currentMode == AppMode.Run) {
                                            onConfigUpdated?.invoke()
                                            pending.acknowledge()
                                        } else {
                                            CloudConfigInbox.offer(pending)
                                        }
                                    } else {
                                        ShowMessage("云端配置保存失败，将自动重试", isChecking = false)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ShowMessage("云端配置同步失败: ${e.message}", isChecking = false)
                }
                delay(30_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

/** Cancel the underlying socket when its owning sync job is stopped. */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, cancelledResponse, _ -> cancelledResponse.close() }
        }
    })
}
