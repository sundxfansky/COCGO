package com.coc.suncode.loadjar.features

import dalvik.system.DexClassLoader

/** Only the selected feature is child-owned; core singletons always come from the active core. */
class FeatureClassLoader(path: String, cache: String, parent: ClassLoader, private val spec: FeatureSpec) :
    DexClassLoader(path, cache, null, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (!spec.owns(name)) return@synchronized parent.loadClass(name)
        val loaded = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(loaded)
        loaded
    }
}
