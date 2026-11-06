package com.coc.suncode.core.data.cloud

import android.content.SharedPreferences
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Checks only the remote version until a different configuration needs to be downloaded. */
class CloudConfigSync(
    private val endpoint: String,
    private val token: String,
    private val deviceId: String,
    private val deviceName: String,
    private val renameDevice: Boolean,
    private val actions: ServerActions,
    private val versions: SharedPreferences,
    private val onRegisteredName: (String) -> Unit,
    private val applyConfig: suspend (JsonObject, Long) -> Boolean,
    private val onStatus: (String, String, Long?) -> Unit
) {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (endpoint.isBlank() || token.isBlank() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val key = "web_v2_" + CloudConfigPolicy.digest("$endpoint:$token:$deviceId")
            var acknowledged = versions.getLong(key, -1L)
            // A cached revision applies only to the exact disk snapshot that was successfully persisted.
            if (versions.getString("${key}_snapshot", "") != CloudConfigPolicy.fingerprint(actions.configJson)) acknowledged = -1L
            val poller = CloudConfigPoller(
                deviceId, deviceName, renameDevice, acknowledged,
                request = ::getJson,
                apply = { config, version -> withContext(Dispatchers.Main.immediate) { applyConfig(config, version) } },
                acknowledge = { version, config ->
                    // Persist metadata only after the host root backend confirms its atomic write.
                    versions.edit().putLong(key, version).putString("${key}_snapshot", CloudConfigPolicy.fingerprint(config)).apply()
                },
                registeredName = { name -> withContext(Dispatchers.Main.immediate) { onRegisteredName(name) } }
            )
            while (isActive) {
                try {
                    val result = poller.checkOnce()
                    withContext(Dispatchers.Main.immediate) { onStatus(result.status, result.message, result.version) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val detail = error.message ?: "云端配置同步失败"
                    withContext(Dispatchers.Main.immediate) { onStatus("error", detail, poller.appliedVersion()) }
                    ShowMessage("云端配置同步失败: $detail", isChecking = false)
                }
                delay(30_000)
            }
        }
    }

    private suspend fun getJson(path: String, method: String = "GET", payload: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(endpoint.trimEnd('/') + path)
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache")
            .method(method, payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 401) throw IOException("云端会话已到期或注销，请更新 Token")
            if (!response.isSuccessful) throw IOException("云端请求失败（${response.code}）")
            val body = response.body
            if (body.contentLength() > MAX_CONFIG_BYTES) throw IOException("云端配置超过大小限制")
            val source = body.source()
            source.request(MAX_CONFIG_BYTES + 1)
            if (source.buffer.size > MAX_CONFIG_BYTES) throw IOException("云端配置超过大小限制")
            JsonParser.parseString(source.readUtf8()).asJsonObject
        }
    }

    fun stop() { job?.cancel(); job = null }

    private companion object { const val MAX_CONFIG_BYTES = 8 * 1024 * 1024L }
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
