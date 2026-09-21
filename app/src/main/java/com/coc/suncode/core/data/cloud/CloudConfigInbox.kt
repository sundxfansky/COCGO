package com.coc.suncode.core.data.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.google.gson.JsonObject

/** Main-thread inbox separates settings drafts from incoming remote snapshots. */
object CloudConfigInbox {
    data class Pending(val version: Int, val config: JsonObject, val actions: ServerActions, val acknowledge: () -> Unit)
    var pending by mutableStateOf<Pending?>(null)
        private set
    fun offer(value: Pending) { if (pending?.version != value.version) pending = value }
    fun keepLocal() { pending?.acknowledge?.invoke(); pending = null }
    fun clear() { pending = null }
    suspend fun applyRemote() {
        val value = pending ?: return
        if (!value.actions.persistConfig(value.config)) {
            ShowMessage("云端配置保存失败，请重试", isChecking = false)
            return
        }
        GlobalVars.pluginUI?.refreshConfigs(value.actions)
        value.acknowledge()
        if (pending === value) pending = null
    }
}
