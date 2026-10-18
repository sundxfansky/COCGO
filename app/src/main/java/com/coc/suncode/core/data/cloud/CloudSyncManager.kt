package com.coc.suncode.core.data.cloud

import android.content.Context
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.data.database.GlobalVars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-owned sync remains active while the floating services outlive MainActivity. */
object CloudSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sync: CloudConfigSync? = null
    fun ensureStarted(context: Context) { if (sync == null) restart(context) }
    fun stop() {
        sync?.stop()
        sync = null
        CloudConfigInbox.clear()
    }
    fun restart(context: Context) {
        stop()
        val application = context.applicationContext
        val preferences = application.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val actions = GlobalVars.serverActions ?: return
        val fallback = BuildConfig.BASE_URL.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/api/v1").orEmpty()
        val endpoint = preferences.getString("base_url", fallback).orEmpty().trimEnd('/')
        val token = preferences.getString("token", "").orEmpty()
        if (endpoint.isBlank() || token.isBlank()) return
        sync = CloudConfigSync(endpoint, token, actions, application.getSharedPreferences("cloud_versions", Context.MODE_PRIVATE)) {
            GlobalVars.pluginUI?.refreshConfigs(actions)
        }.also { it.start(scope) }
    }
}
