package com.coc.suncode.core.data.cloud

import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest

/** Shared validation keeps the embedded editor and the polling service on the same configuration contract. */
object CloudConfigPolicy {
    fun endpoint(value: String): String {
        val input = value.trim().trimEnd('/')
        if (input.isEmpty()) return ""
        val uri = runCatching { URI(input) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "请输入完整的 HTTP / HTTPS 服务端地址，不包含账号、查询参数或片段"
        }
        return if (uri.path.orEmpty().trimEnd('/').endsWith("/api/v1")) input else "$input/api/v1"
    }

    fun fingerprint(config: JsonObject): String {
        // Key order is not part of the revision identity.
        val canonical = JsonObject()
        config.keySet().sorted().forEach { canonical.add(it, config.get(it)) }
        return digest(canonical.toString())
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate(config: JsonObject) {
        require(config.entrySet().all { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isString }) {
            "配置必须是字符串键值对象"
        }
        config.get("account_count")?.let {
            require(it.asString.toIntOrNull() in 1..10_000) { "账号数量必须为 1 到 10000" }
        }
    }
}
