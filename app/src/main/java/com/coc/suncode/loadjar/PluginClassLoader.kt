package com.coc.suncode.loadjar

import dalvik.system.DexClassLoader

/** Plugin implementation classes are isolated; Android, Compose and the host ABI remain parent-owned. */
class PluginClassLoader(path: String, cache: String, parent: ClassLoader) : DexClassLoader(path, cache, null, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (!name.startsWith("com.coc.suncode.jar.")) return@synchronized super.loadClass(name, resolve)
        val loaded = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(loaded)
        loaded
    }
}
