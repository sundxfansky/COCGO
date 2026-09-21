package com.coc.suncode.core.system.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coc.suncode.BuildConfig
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.loadjar.Loadjar
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != "com.coc.suncode.SUN_RELOAD") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            val previous = AppStateManager.currentMode
            try {
                // A debug reload uses the same stop barrier as a signed hot update.
                AppStateManager.setMode(AppMode.Main)
                BotRuntime.stop()
                Loadjar(context).startLoading { ShowMessage(it, isChecking = false) }
            } finally { AppStateManager.setMode(previous); pending.finish() }
        }
    }
}
