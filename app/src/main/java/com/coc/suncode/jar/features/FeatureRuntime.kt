package com.coc.suncode.jar.features

import android.content.Context
import com.coc.suncode.interfaces.FeatureResult
import com.coc.suncode.loadjar.features.FeatureSession

/** One session per core loader: replacements never reuse another core's account state. */
object FeatureRuntime {
    private lateinit var session: FeatureSession

    fun initialize(context: Context, bundled: Map<String, String>) {
        session = FeatureSession(context.applicationContext, FeatureRuntime::class.java.classLoader!!, bundled)
    }

    fun reload() = session.reload()

    fun requireLogin() = check(session.enabled("login")) { "登录插件已停用，请先在插件管理中启用" }

    suspend fun execute(id: String, action: String, account: Int = 0): FeatureResult =
        session.execute(id, action, account)

    suspend fun run(id: String, action: String): Boolean =
        execute(id, action).let { it == FeatureResult.SUCCESS || it == FeatureResult.SKIPPED }
}
