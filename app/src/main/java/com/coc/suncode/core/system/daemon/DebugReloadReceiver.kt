package com.coc.suncode.core.system.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.loadjar.Loadjar
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.coc.suncode.core.util.fileactions.LogHelper

class DebugReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.coc.suncode.SUN_RELOAD") return

        LogHelper.showDebugInfo("DebugReload: Received reload broadcast")

        // 1. Stop current bot by setting mode to Main
        AppStateManager.setMode(AppMode.Main)

        // 2. Reload JAR
        val loader = Loadjar(context)
        loader.startLoading { status ->
            LogHelper.showDebugInfo("DebugReload: $status")
            if (status == "Plugin loaded successfully") {
                // 3. Re-run bot after reload
                GlobalVars.isPlaying.value = true
                Handler(Looper.getMainLooper()).postDelayed({
                    AppStateManager.setMode(AppMode.Run)
                    LogHelper.showDebugInfo("DebugReload: Bot restarted")
                }, 500)
            }
        }
    }
}
