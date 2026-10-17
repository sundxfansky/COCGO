package com.coc.suncode.loadjar

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.system.daemon.ServerApkInstaller
import com.coc.suncode.core.ui.localcomponents.LocalCustomButton
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.interfaces.MainCode
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import java.io.File
import java.security.MessageDigest

class Loadjar(private val context: Context) {
    companion object { private const val ENTRY = "com.coc.suncode.jar.ui.EnterMainCode" }
    private var pluginUI: MainCode? = null

    @Composable fun LoadAndShowUI(loadStatus: String, onClose: () -> Unit) {
        if (loadStatus == "Plugin loaded successfully") {
            (GlobalVars.pluginUI ?: pluginUI)?.ShowMainUI(context, onClose)
        } else Column {
            Text(loadStatus)
            LocalCustomButton(text = "关闭悬浮窗", onClick = { AppStateManager.setMode(AppMode.Run); onClose() })
        }
    }

    fun startLoading(assetFileName: String = "code.jar", onStatusChange: (String) -> Unit) {
        ShowMessage.record("插件加载开始：默认文件=$assetFileName")
        onStatusChange("加载中...")
        val directory = File(context.filesDir, "assets").apply { mkdirs() }
        // APK assets are trusted by the platform signature and copied atomically into private storage.
        val bundledAssets = context.assets.list("").orEmpty().filter {
            it.endsWith(".jar") || it.endsWith(".jar.sig") || it.endsWith(".tflite")
        }
        ShowMessage.record("插件资源检查：${bundledAssets.size} 个候选资源，目录=${directory.name}")
        bundledAssets.forEach { name ->
            runCatching { ServerApkInstaller.install(File(directory, name)) { context.assets.open(name) } }
                .onFailure { ShowMessage("资源安装失败: $name / ${it.message}", isChecking = false) }
        }
        val candidates = directory.listFiles().orEmpty().filter { PluginPackage.version(it.name) != null }
            .sortedByDescending { PluginPackage.version(it.name) }
        val fallback = File(directory, assetFileName).takeIf { it.exists() }
        ShowMessage.record(
            "插件候选：${candidates.joinToString { it.name }}" +
                (fallback?.let { ", 回退=${it.name}" } ?: "，无回退文件")
        )
        val loaded = (candidates + listOfNotNull(fallback)).any { loadFile(it) }
        val success = when {
            loaded -> true
            GlobalVars.pluginUI != null -> { pluginUI = GlobalVars.pluginUI; true }
            else -> loadBundled()
        }
        ShowMessage.record("插件加载${if (success) "完成" else "失败"}：configLoaded=${GlobalVars.isConfigLoaded}")
        onStatusChange(if (success) "Plugin loaded successfully" else "插件加载失败，已保留原版本")
    }

    /** Load a specific verified candidate; publish only after ABI and configuration initialization succeed. */
    fun loadFile(file: File, requireSignature: Boolean = !BuildConfig.DEBUG): Boolean = try {
        ShowMessage.record("尝试加载插件：${file.name}，签名校验=${requireSignature}")
        require(file.parentFile?.canonicalFile == File(context.filesDir, "assets").canonicalFile)
        require(file.length() in 1..(64L*1024*1024)) { "Invalid plugin size" }
        val bytes = file.readBytes()
        val signature = File(file.path + ".sig")
        if (requireSignature || signature.exists()) {
            require(signature.exists() && BuildConfig.HOT_UPDATE_PUBLIC_KEY.isNotBlank()) { "Missing trusted plugin signature/key" }
            require(PluginPackage.verify(file.name.toByteArray() + byteArrayOf(0) + bytes, signature.readBytes(), Base64.decode(BuildConfig.HOT_UPDATE_PUBLIC_KEY, Base64.DEFAULT))) { "Invalid plugin signature" }
        }
        val dex = if (file.name.startsWith("encrypted_")) PluginPackage.decrypt(bytes, BuildConfig.LEGACY_JAR_KEY) else bytes
        PluginPackage.requireDexJar(dex)
        val hash = MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) }
        val installed = File(context.codeCacheDir, "plugin-$hash.jar")
        ServerApkInstaller.install(installed) { dex.inputStream() }
        val loader = PluginClassLoader(installed.path, context.codeCacheDir.path, context.classLoader)
        publish(loader.loadClass(ENTRY).getDeclaredConstructor().newInstance() as MainCode)
        ShowMessage.record("插件加载成功：${file.name}，sha256=${hash.take(12)}，configLoaded=${GlobalVars.isConfigLoaded}")
        true
    } catch (error: Exception) {
        ShowMessage.record("插件加载失败，保留原版本：${file.name}：${error.message}", throwable = error)
        ShowMessage("插件加载失败，保留原版本: ${file.name} / ${error.message}", isChecking = false)
        false
    } catch (error: LinkageError) {
        ShowMessage.record("插件接口不兼容，保留原版本：${file.name}：${error.message}", throwable = error)
        ShowMessage("插件接口不兼容，保留原版本: ${file.name} / ${error.message}", isChecking = false)
        false
    }

    private fun publish(instance: MainCode) {
        // Validate the host ABI without resetting an open settings draft.
        require(instance.javaClass.getMethod("refreshConfigs", ServerActions::class.java).declaringClass != MainCode::class.java)
        val wasLoaded = GlobalVars.isConfigLoaded
        try {
            withConfigRollback(GlobalVars.configStates) {
                val actions = GlobalVars.serverActions
                if (actions != null && !actions.isLoading) instance.refreshConfigs(actions)
                else GlobalVars.isConfigLoaded = false
            }
        } catch (error: Throwable) {
            // Failed plugin initialization must not leave a partial Schema in the shared host state.
            GlobalVars.isConfigLoaded = wasLoaded
            throw error
        }
        pluginUI = instance
        GlobalVars.pluginUI = instance
    }

    private fun loadBundled(): Boolean = try {
        // Reflection keeps the host independent of plugin implementation classes.
        ShowMessage.record("尝试加载宿主内置插件")
        publish(context.classLoader.loadClass(ENTRY).getDeclaredConstructor().newInstance() as MainCode)
        ShowMessage.record("宿主内置插件加载成功：configLoaded=${GlobalVars.isConfigLoaded}")
        true
    } catch (error: Exception) {
        ShowMessage.record("内置插件加载失败：${error.message}", throwable = error)
        ShowMessage("内置插件加载失败: ${error.message}", isChecking = false)
        GlobalVars.pluginUI != null
    }
}
