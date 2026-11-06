package com.coc.suncode.core.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.cloud.CloudConfigPolicy
import com.coc.suncode.core.data.cloud.CloudSyncManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream

/** The only web-visible operations are configuration snapshots, conditional saves and connection preferences. */
class LocalSettingsController(
    private val context: Context,
    private val savedSnapshot: () -> JsonObject,
    private val normalize: (JsonObject) -> JsonObject,
    private val persist: suspend (JsonObject, JsonObject) -> Boolean?,
    private val schema: () -> JsonObject
) {
    suspend fun request(method: String, params: JsonObject): JsonObject = CloudSyncManager.settingsMutex.withLock {
        when (method) {
            "getState" -> state()
            "getVersion" -> state(includeConfig = false)
            "saveConfig" -> {
                if (CloudSyncManager.remoteEnabled) throw SettingsRequestException(403, "已启用远端同步，请在服务端页面修改设置")
                val current = savedSnapshot().deepCopy()
                if (params.get("version")?.asString != CloudConfigPolicy.fingerprint(current)) {
                    throw SettingsRequestException(409, "设置已更新，请重新载入后再保存")
                }
                val incoming = params.getAsJsonObject("config") ?: throw IllegalArgumentException("缺少配置内容")
                CloudConfigPolicy.validate(incoming)
                // Retain settings added by newer modules when an older editor cannot render them yet.
                val merged = normalize(current).apply { incoming.entrySet().forEach { (key, value) -> add(key, value) } }
                when (persist(current, merged)) {
                    null -> throw SettingsRequestException(409, "设置已更新，请重新载入后再保存")
                    false -> throw SettingsRequestException(500, "配置保存失败，请检查 Root 后端后重试")
                    true -> Unit
                }
                state()
            }
            "setConnection" -> {
                CloudSyncManager.configure(
                    context, params.get("endpoint")?.asString.orEmpty(), params.get("enabled")?.asBoolean == true,
                    params.get("token")?.takeUnless { it.isJsonNull }?.asString,
                    params.get("deviceName")?.takeUnless { it.isJsonNull }?.asString
                )
                state()
            }
            else -> throw SettingsRequestException(404, "不支持的设置操作")
        }
    }

    private fun state(includeConfig: Boolean = true): JsonObject {
        val config = savedSnapshot()
        return JsonObject().apply {
            if (includeConfig) {
                add("config", normalize(config))
                add("schema", schema())
            }
            addProperty("version", CloudConfigPolicy.fingerprint(config))
            addProperty("readOnly", CloudSyncManager.remoteEnabled)
            add("connection", CloudSyncManager.connection(context))
            add("sync", JsonObject().apply {
                addProperty("status", CloudSyncManager.status)
                addProperty("message", CloudSyncManager.message)
                addProperty("version", CloudSyncManager.remoteVersion)
                addProperty("lastCheckedAt", CloudSyncManager.lastCheckedAt)
            })
        }
    }
}

private class SettingsRequestException(val status: Int, message: String) : Exception(message)

/** Uses a virtual HTTPS asset origin; the root worker never exposes a socket or web-accessible API. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsWebView(controller: LocalSettingsController, modifier: Modifier = Modifier, onInteraction: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val interaction = rememberUpdatedState(onInteraction)
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    if (!supported) {
        Text("请更新 Android System WebView 后使用设置页面。", modifier)
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Enable standard WebView inspection only in debug builds; release pages are never inspectable.
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                // AndroidView can intercept input before Compose observes it; stop countdowns without consuming input.
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) interaction.value()
                    false
                }
                setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) interaction.value()
                    false
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        // Even subresources are restricted to bundled assets, keeping the privileged origin isolated.
                        return loader.shouldInterceptRequest(request.url)?.takeIf { request.url.path.orEmpty().startsWith("/assets/settings/") } ?: WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                WebViewCompat.addWebMessageListener(this, "SunSettings", setOf(ASSET_ORIGIN)) { _, message, origin, mainFrame, reply ->
                    if (!mainFrame || origin.toString().trimEnd('/') != ASSET_ORIGIN) return@addWebMessageListener
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.length > 8 * 1024 * 1024) return@addWebMessageListener
                    scope.launch {
                        val response = JsonObject()
                        try {
                            val envelope = JsonParser.parseString(raw).asJsonObject
                            response.add("id", envelope.get("id"))
                            val result = controller.request(envelope.get("method")?.asString.orEmpty(), envelope.getAsJsonObject("params") ?: JsonObject())
                            response.add("result", result)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            response.add("error", JsonObject().apply {
                                addProperty("message", error.message ?: "设置操作失败")
                                addProperty("status", (error as? SettingsRequestException)?.status ?: 400)
                            })
                        }
                        reply.postMessage(response.toString())
                    }
                }
                loadUrl("$ASSET_ORIGIN/assets/settings/index.html")
            }
        },
        onRelease = { view ->
            view.stopLoading()
            WebViewCompat.removeWebMessageListener(view, "SunSettings")
            view.destroy()
        }
    )
}

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
