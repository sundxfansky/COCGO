package com.coc.suncode.core.data.cloud

import com.google.gson.JsonObject
import java.io.IOException

internal data class ConfigPollStatus(val status: String, val message: String, val version: Long?)

/** A single polling transaction is testable independently from Android lifecycle and wall-clock delays. */
internal class CloudConfigPoller(
    private val deviceId: String,
    private val deviceName: String,
    private val renameDevice: Boolean,
    initialVersion: Long,
    private val request: suspend (String, String, JsonObject?) -> JsonObject,
    private val apply: suspend (JsonObject, Long) -> Boolean,
    private val acknowledge: (Long, JsonObject) -> Unit,
    private val registeredName: suspend (String) -> Unit
) {
    private var acknowledged = initialVersion
    private var unpublishedVersion: Long? = null
    private var registered = false
    private val configPath = "/devices/$deviceId/config"

    suspend fun checkOnce(): ConfigPollStatus {
        if (!registered) {
            val identity = JsonObject().apply { addProperty("id", deviceId); addProperty("name", deviceName) }
            val registration = request("/devices", "POST", identity)
            val canonical = if (renameDevice) {
                request("/devices/$deviceId", "PATCH", JsonObject().apply { addProperty("name", deviceName) })
            } else registration
            registeredName(canonical.getAsJsonObject("device")?.get("name")?.asString ?: deviceName)
            registered = true
        }
        val next = versionOf(request("$configPath/version", "GET", null))
        if (next != acknowledged && next != unpublishedVersion) {
            val snapshot = request(configPath, "GET", null)
            val downloadedVersion = versionOf(snapshot)
            val config = snapshot.getAsJsonObject("config") ?: throw IOException("服务端未返回配置内容")
            CloudConfigPolicy.validate(config)
            if (downloadedVersion == 0L && config.size() == 0) {
                // A new device has no published settings; keep its local setup until the first cloud save.
                unpublishedVersion = downloadedVersion
            } else {
                if (!apply(config, downloadedVersion)) throw IOException("云端配置保存失败，将自动重试")
                acknowledge(downloadedVersion, config)
                acknowledged = downloadedVersion
                unpublishedVersion = null
            }
        }
        return if (next == unpublishedVersion) ConfigPollStatus("waiting", "服务端尚未发布配置，正在保留本机设置", null)
        else ConfigPollStatus("synced", "", acknowledged)
    }

    fun appliedVersion(): Long? = acknowledged.takeIf { it >= 0 }

    private fun versionOf(body: JsonObject): Long {
        val value = body.get("version")?.takeIf { it.isJsonPrimitive }?.asString
        return value?.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("服务端配置版本无效")
    }
}
