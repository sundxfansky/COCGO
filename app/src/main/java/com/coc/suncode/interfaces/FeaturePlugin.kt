package com.coc.suncode.interfaces

import android.content.Context

/** Stable host ABI. Business implementations and their dependencies live in feature JARs. */
interface FeaturePlugin {
    suspend fun execute(context: Context, action: String, account: Int): FeatureResult
}

enum class FeatureResult { SUCCESS, FAILED, SKIPPED, AUTHORIZING }

/** Optional extension: old monolithic plugins remain loadable by the host. */
interface FeaturePluginClient {
    fun initializeFeatures(context: Context, bundledPlugins: Map<String, String>)
    fun reloadFeatures()
}
