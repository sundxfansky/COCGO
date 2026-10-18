package com.coc.suncode.loadjar.features

import android.content.Context
import android.util.Base64
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.system.daemon.ServerApkInstaller
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.loadjar.PluginPackage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** Host-owned private storage. Installation commits a pointer only after verification and linking. */
class FeatureStore(private val context: Context) {
    companion object {
        private val installMutex = Mutex()
    }
    private val preferences = context.getSharedPreferences("feature_plugins", Context.MODE_PRIVATE)
    val specs = FeatureSpec.read(context)

    fun enabled(id: String): Boolean = preferences.getBoolean("enabled.$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        require(specs.any { it.id == id })
        check(preferences.edit().putBoolean("enabled.$id", enabled).commit())
    }

    fun installed(id: String): File? {
        val name = preferences.getString("installed.$id", null) ?: return null
        require(name.matches(Regex("[a-f0-9]{64}\\.jar")))
        return File(directory(), name)
    }

    fun version(id: String): String = installed(id)?.let {
        runCatching { FeatureArchive.metadata(it.readBytes()).version.toString() }.getOrDefault("文件损坏")
    } ?: "内置"

    fun useBundled(id: String) {
        require(specs.any { it.id == id })
        check(preferences.edit().remove("installed.$id").commit())
    }

    fun verify(file: File, id: String): FeatureMetadata {
        val bytes = file.inputStream().use { it.readBytesLimited(FeatureArchive.LIMIT) }
        val metadata = FeatureArchive.metadata(bytes)
        require(metadata.id == id) { "插件 ID 不匹配" }
        val signature = File(file.path + ".sig")
        if (!BuildConfig.DEBUG || signature.exists()) {
            require(signature.exists() && BuildConfig.HOT_UPDATE_PUBLIC_KEY.isNotBlank()) { "未配置插件签名或公钥" }
            val signed = signature.inputStream().use { it.readBytesLimited(16 * 1024) }
            require(PluginPackage.verify(bytes, signed, Base64.decode(BuildConfig.HOT_UPDATE_PUBLIC_KEY, Base64.DEFAULT))) {
                "插件签名无效"
            }
        }
        return metadata
    }

    suspend fun installUrl(id: String, url: String, parent: ClassLoader): Long = installMutex.withLock {
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "插件地址必须使用 HTTPS" }
            val spec = specs.first { it.id == id }
            val client = OkHttpClient.Builder().followRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
            fun download(address: String, limit: Int): ByteArray =
                client.newCall(Request.Builder().url(address).build()).execute().use { response ->
                    check(response.isSuccessful) { "下载失败 HTTP ${response.code}" }
                    response.body.byteStream().use { it.readBytesLimited(limit) }
                }
            val bytes = download(url, FeatureArchive.LIMIT)
            val signature = download("$url.sig", 16 * 1024)
            currentCoroutineContext().ensureActive()
            require(BuildConfig.HOT_UPDATE_PUBLIC_KEY.isNotBlank()) { "宿主未配置更新公钥" }
            require(PluginPackage.verify(bytes, signature, Base64.decode(BuildConfig.HOT_UPDATE_PUBLIC_KEY, Base64.DEFAULT))) { "插件签名无效" }
            val metadata = FeatureArchive.metadata(bytes)
            require(metadata.id == id) { "所选功能与插件包不匹配" }
            installed(id)?.let { require(metadata.version > verify(it, id).version) { "请安装更高版本，或先恢复内置版本" } }
            val target = cache(bytes)
            // A fresh child loader validates the entry without publishing it to a running session.
            val loader = FeatureClassLoader(target.path, context.codeCacheDir.path, parent, spec)
            try {
                require(loader.loadClass(spec.entry).getDeclaredConstructor().newInstance() is FeaturePlugin)
            } catch (error: LinkageError) {
                throw IllegalArgumentException("插件与当前核心不兼容", error)
            }
            val stored = File(directory(), target.name)
            ServerApkInstaller.install(File(stored.path + ".sig")) { signature.inputStream() }
            ServerApkInstaller.install(stored) { bytes.inputStream() }
            currentCoroutineContext().ensureActive()
            check(preferences.edit().putString("installed.$id", stored.name).commit())
            ShowMessage.record("功能插件已安装：$id，版本=${metadata.version}")
            metadata.version
        }
    }

    fun cache(bytes: ByteArray): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = File(context.codeCacheDir, "features").apply { mkdirs() }
        return File(directory, "$hash.jar").also { target -> ServerApkInstaller.install(target) { bytes.inputStream() } }
    }

    private fun directory() = File(context.filesDir, "feature_plugins").apply { mkdirs() }
}
