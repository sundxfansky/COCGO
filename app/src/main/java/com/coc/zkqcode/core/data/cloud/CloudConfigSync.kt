package com.coc.zkqcode.core.data.cloud

import com.coc.zkqcode.core.data.websocket.ServerActions
import com.coc.zkqcode.core.util.basic.ShowMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Polls the cloud config endpoint and forwards newer JSON to the local config server. */
class CloudConfigSync(private val endpoint: String, private val token: String, private val actions: ServerActions) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (endpoint.isBlank() || token.isBlank() || job != null) return
        job = scope.launch(Dispatchers.IO) {
            var version = 0
            while (isActive) {
                try {
                    val request = Request.Builder().url(endpoint.trimEnd('/') + "/config")
                        .header("Authorization", "Bearer $token").header("If-None-Match", version.toString()).build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 200) {
                            val body = response.body?.string() ?: return@use
                            val root = gson.fromJson(body, JsonObject::class.java)
                            val next = root.get("version")?.asInt ?: version
                            if (next > version && root.has("config")) { actions.updateConfig(root.getAsJsonObject("config")); version = next }
                        }
                    }
                } catch (e: Exception) { ShowMessage("云端配置同步失败: ${e.message}") }
                delay(30_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
