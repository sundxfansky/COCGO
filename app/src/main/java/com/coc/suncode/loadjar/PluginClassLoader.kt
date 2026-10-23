package com.coc.suncode.loadjar

import dalvik.system.DexClassLoader
import java.io.InputStream
import java.net.URL

/** Plugin implementation classes are isolated; Android, Compose and the host ABI remain parent-owned. */
class PluginClassLoader(path: String, cache: String, parent: ClassLoader) : DexClassLoader(path, cache, null, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (!name.startsWith("com.coc.suncode.jar.")) return@synchronized super.loadClass(name, resolve)
        val loaded = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(loaded)
        loaded
    }

    // Plugin resources (e.g. block-match template images) must come from the plugin jar,
    // not from the older copy bundled in the APK that the parent would return first.
    override fun getResource(name: String): URL? =
        if (name.startsWith(PLUGIN_RESOURCE_PREFIX)) findResource(name) ?: super.getResource(name) else super.getResource(name)

    override fun getResourceAsStream(name: String): InputStream? = getResource(name)?.openStream()

    private companion object {
        const val PLUGIN_RESOURCE_PREFIX = "com/coc/suncode/jar/"
    }
}
