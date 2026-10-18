package com.coc.suncode.loadjar.features

import android.content.Context
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.interfaces.FeaturePlugin
import com.coc.suncode.interfaces.FeatureResult
import java.io.File

class FeatureSession(
    private val context: Context,
    private val parent: ClassLoader,
    private val bundled: Map<String, String>
) {
    private val store = FeatureStore(context)
    init {
        // Validate fallbacks before the host publishes a new core generation.
        store.specs.forEach { spec ->
            val path = bundled[spec.id]
            if (bundled.isNotEmpty()) require(path != null) { "Missing bundled feature: ${spec.id}" }
            val loader = if (path == null) parent else FeatureClassLoader(path, context.codeCacheDir.path, parent, spec)
            require(FeaturePlugin::class.java.isAssignableFrom(loader.loadClass(spec.entry)))
        }
    }
    private val dispatcher = FeatureDispatcher(store::enabled) { id ->
        val plugin = load(store.specs.first { it.id == id })
        val action: suspend (String, Int) -> FeatureResult = { action, account -> plugin.execute(context, action, account) }
        action
    }

    fun enabled(id: String) = store.enabled(id)

    /** Called only after the host stop barrier; in-flight feature invocations retain their code. */
    fun reload() = dispatcher.reload()

    suspend fun execute(id: String, action: String, account: Int): FeatureResult {
        require(store.specs.any { it.id == id }) { "Unknown feature: $id" }
        if (id == "login" && !store.enabled(id)) {
            ShowMessage("登录插件已停用，请在功能插件中启用", isChecking = false)
        }
        return dispatcher.execute(id, action, account)
    }

    private fun load(spec: FeatureSpec): FeaturePlugin {
        fun instantiate(path: String?): FeaturePlugin {
            val loader = if (path == null) parent else FeatureClassLoader(path, context.codeCacheDir.path, parent, spec)
            return loader.loadClass(spec.entry).getDeclaredConstructor().newInstance() as FeaturePlugin
        }
        store.installed(spec.id)?.let { file ->
            try {
                store.verify(file, spec.id)
                return instantiate(store.cache(file.readBytes()).path)
            } catch (error: Exception) {
                ShowMessage.record("${spec.name}插件加载失败，回退内置版本：${error.message}")
            } catch (error: LinkageError) {
                ShowMessage.record("${spec.name}插件接口不兼容，回退内置版本：${error.message}")
            }
        }
        val path = bundled[spec.id]
        if (path != null) require(FeatureArchive.metadata(File(path).readBytes()).id == spec.id)
        return instantiate(path)
    }
}
