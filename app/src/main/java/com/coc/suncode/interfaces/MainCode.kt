package com.coc.suncode.interfaces

import android.content.Context
import androidx.compose.runtime.Composable
import com.coc.suncode.core.data.websocket.ServerActions

interface MainCode {
    @Composable
    fun ShowMainUI(context: Context, onClose: () -> Unit) // 改为传入 Context 和 onClose 回调

    /** Host calls only the active plugin, never its bundled Schema implementation. */
    fun refreshConfigs(actions: ServerActions)

    suspend fun runBot()
}