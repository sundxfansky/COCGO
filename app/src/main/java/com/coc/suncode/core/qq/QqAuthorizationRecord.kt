package com.coc.suncode.core.qq

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A locally timed grant; replaying a grant must never extend its expiry. */
data class QqAuthorizationRecord(
    val accessToken: String,
    val openId: String,
    val payToken: String,
    val expiresIn: Long,
    val capturedAt: Long
) {
    val expiresAt: Long get() = capturedAt + expiresIn * 1000L
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt || now < capturedAt

    // The companion APK requires this exact order and a trailing ampersand.
    fun toolInput(): String = "access_token=$accessToken&expires_in=$expiresIn&openid=$openId&pay_token=$payToken&"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("access_token", accessToken)
        addProperty("openid", openId)
        addProperty("pay_token", payToken)
        addProperty("expires_in", expiresIn)
        addProperty("captured_at", capturedAt)
    }

    override fun toString(): String = "QqAuthorizationRecord(expiresAt=$expiresAt)"

    companion object {
        private val safeValue = Regex("[A-Za-z0-9._~-]{1,4096}")

        fun fromResponse(response: String, now: Long = System.currentTimeMillis()): QqAuthorizationRecord {
            require(response.length <= 32_768) { "授权返回数据过长" }
            val json = JsonParser.parseString(response).asJsonObject
            require(json.get("ret")?.asString == "0") { "QQ 授权未成功" }
            return parse(json, now)
        }

        fun fromStored(json: JsonObject): QqAuthorizationRecord = parse(json, json.get("captured_at").asLong)

        private fun parse(json: JsonObject, capturedAt: Long): QqAuthorizationRecord {
            fun field(name: String): String = json.get(name)?.asString.orEmpty().also {
                require(safeValue.matches(it)) { "QQ 授权缺少有效字段" }
            }
            val duration = json.get("expires_in")?.asString?.toLongOrNull() ?: 0L
            require(duration in 1..315_360_000L && capturedAt > 0 && capturedAt < Long.MAX_VALUE - duration * 1000L) {
                "QQ 授权有效期无效"
            }
            return QqAuthorizationRecord(field("access_token"), field("openid"), field("pay_token"), duration, capturedAt)
        }
    }
}
