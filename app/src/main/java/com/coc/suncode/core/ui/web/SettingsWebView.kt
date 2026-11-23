package com.coc.suncode.core.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.delay
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
    internal fun settingsVersion(): String = CloudConfigPolicy.fingerprint(savedSnapshot())

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

internal class SettingsRequestException(val status: Int, message: String) : Exception(message)

/** Uses a virtual HTTPS asset origin; the root worker never exposes a socket or web-accessible API. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsWebView(controller: LocalSettingsController, modifier: Modifier = Modifier, onInteraction: () -> Unit = {}) {
    SettingsWebPanel(modifier = modifier, onInteraction = onInteraction, request = controller::request)
}

/** The same local asset page serves settings, startup and authorization status. */
@Composable
@SuppressLint("SetJavaScriptEnabled")
fun SettingsWebPanel(
    modifier: Modifier = Modifier,
    surface: String = "settings",
    onInteraction: () -> Unit = {},
    beforeFeedback: () -> Unit = {},
    request: suspend (String, JsonObject) -> JsonObject
) {
    val scope = rememberCoroutineScope()
    val interaction = rememberUpdatedState(onInteraction)
    val prepareFeedback = rememberUpdatedState(beforeFeedback)
    val currentRequest = rememberUpdatedState(request)
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Enable standard WebView inspection only in debug builds; release pages are never inspectable.
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                if (surface in setOf("overlay", "logs", "message")) setBackgroundColor(Color.TRANSPARENT)
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
                if (supported) WebViewCompat.addWebMessageListener(this, "SunSettings", setOf(ASSET_ORIGIN)) { _, message, origin, mainFrame, reply ->
                    if (!mainFrame || origin.toString().trimEnd('/') != ASSET_ORIGIN) return@addWebMessageListener
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.length > 8 * 1024 * 1024) return@addWebMessageListener
                    scope.launch {
                        val response = JsonObject()
                        try {
                            val envelope = JsonParser.parseString(raw).asJsonObject
                            response.add("id", envelope.get("id"))
                            val method = envelope.get("method")?.asString.orEmpty()
                            val params = envelope.getAsJsonObject("params") ?: JsonObject()
                            // Keep the page and its draft mounted while capturing the layer underneath.
                            val result = if (method == "submitFeedback" && visibility == View.VISIBLE) {
                                check(BuildConfig.BASE_URL.isNotBlank()) { "宿主未配置反馈服务" }
                                visibility = View.INVISIBLE
                                try {
                                    prepareFeedback.value()
                                    delay(300)
                                    currentRequest.value(method, params)
                                } finally {
                                    visibility = View.VISIBLE
                                }
                            } else if (method == "submitFeedback") {
                                throw SettingsRequestException(409, "截图正在提交")
                            } else currentRequest.value(method, params)
                            response.add("result", result)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            response.add("error", JsonObject().apply {
                                addProperty("message", error.message ?: "设置操作失败")
                                addProperty("status", (error as? SettingsRequestException)?.status ?: 400)
                            })
                        }
                        if (isAttachedToWindow) reply.postMessage(response.toString())
                    }
                }
                val page = if (supported) surface else "unsupported"
                loadUrl("$ASSET_ORIGIN/assets/settings/index.html?surface=$page")
            }
        },
        onRelease = { view ->
            view.stopLoading()
            if (supported) WebViewCompat.removeWebMessageListener(view, "SunSettings")
            view.destroy()
        }
    )
}

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
