package com.coc.suncode.core.system.hotupdate

import android.content.Context
import android.util.Base64
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.daemon.ServerApkInstaller
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.loadjar.Loadjar
import com.coc.suncode.loadjar.PluginPackage
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object HotUpdateManager {
    private val updateMutex = Mutex()
    @Volatile private var lastSignalTime = System.currentTimeMillis()
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun listenForSignal(context: Context) {
        GlobalVars.updateCheckSignal.collect { deferred ->
            try {
                lastSignalTime = System.currentTimeMillis()
                updateMutex.withLock { checkAndUpdate(context) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ShowMessage("更新检查失败: ${e.message}", isChecking = false) }
            finally { deferred.complete(Unit) }
        }
    }

    suspend fun startWatchdog(context: Context) {
        while (true) {
            delay(30 * 60_000L)
            if (GlobalVars.configStates["auto_update"]?.value == "2" && System.currentTimeMillis() - lastSignalTime >= 8 * 3600_000L) {
                try { updateMutex.withLock { checkAndUpdate(context) }; lastSignalTime = System.currentTimeMillis() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ShowMessage("自动更新失败: ${e.message}", isChecking = false) }
            }
        }
    }

    private suspend fun checkAndUpdate(context: Context) {
        if (AppStateManager.currentMode != AppMode.Run) {
            ShowMessage("请先保存并关闭设置界面，再进行热更新", isChecking = false)
            return
        }
        val preferences = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val endpoint = preferences.getString("base_url", BuildConfig.BASE_URL.trimEnd('/') + "/api/v1").orEmpty().trimEnd('/')
        val token = preferences.getString("token", "").orEmpty()
        if (token.isBlank() || BuildConfig.HOT_UPDATE_PUBLIC_KEY.isBlank()) {
            ShowMessage("热更新需要云端登录 Token 和构建时配置的更新公钥", isChecking = false)
            return
        }
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://127.0.0.1:") || endpoint.startsWith("http://localhost:")) { "更新服务必须使用 HTTPS" }
        fun request(path: String) = Request.Builder().url("$endpoint/$path").header("Authorization", "Bearer $token").build()
        val manifest = JsonParser.parseString(String(client.newCall(request("plugins/latest")).bytes(64*1024))).asJsonObject
        val name = manifest.get("fileName").asString
        val version = PluginPackage.version(name) ?: error("无效的插件文件名")
        require(name.startsWith("encrypted_"))
        val applied = context.getSharedPreferences("plugin", Context.MODE_PRIVATE)
        if (version <= applied.getLong("version", 0)) { ShowMessage("当前已是最新版本"); return }
        val bytes = client.newCall(request("plugins/$version")).bytes(64*1024*1024)
        val signature = Base64.decode(manifest.get("signature").asString, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == manifest.get("sha256").asString) { "插件校验失败" }
        require(PluginPackage.verify(name.toByteArray() + byteArrayOf(0) + bytes, signature, Base64.decode(BuildConfig.HOT_UPDATE_PUBLIC_KEY, Base64.DEFAULT))) { "插件签名无效" }
        val directory = File(context.filesDir, "assets").apply { mkdirs() }
        val target = File(directory, name)
        ServerApkInstaller.install(File(target.path + ".sig")) { signature.inputStream() }
        ServerApkInstaller.install(target) { bytes.inputStream() }
        // Stop and join the old script before changing its class loader; never delete the rollback artifact first.
        withContext(Dispatchers.Main + NonCancellable) {
            // Settings may have opened while the network requests were suspended.
            if (AppStateManager.currentMode != AppMode.Run) {
                ShowMessage("下载已完成，请保存并关闭设置后重新检查更新", isChecking = false)
                return@withContext
            }
            val previousMode = AppStateManager.currentMode
            try {
                AppStateManager.setMode(AppMode.Main)
                BotRuntime.stop()
                if (Loadjar(context).loadFile(target, requireSignature = true)) {
                    applied.edit().putLong("version", version).apply()
                    ShowMessage("新版已加载", isChecking = false)
                } else {
                    target.delete()
                    File(target.path + ".sig").delete()
                    ShowMessage("新版加载失败，继续使用原版本", isChecking = false)
                }
            } finally {
                AppStateManager.setMode(previousMode)
            }
        }
    }

    private suspend fun Call.bytes(limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful) { "HTTP ${it.code}" }
                        it.body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= limit) { "Response exceeds limit" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
