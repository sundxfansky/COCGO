package com.coc.suncode.core.util.exit

import android.content.Context
import android.content.Intent
import android.util.Log
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.daemon.DaemonService
import com.coc.suncode.core.ui.floatingwindows.ControlWindowService
import com.coc.suncode.core.ui.floatingwindows.MessageBoxService
import com.coc.suncode.core.ui.floatingwindows.UIWindowService
import com.coc.suncode.core.system.screencapture.ScreenCaptureManager
import com.coc.suncode.core.system.input.RootTouchClient
import com.coc.suncode.core.util.basic.ShowMessage
import com.topjohnwu.superuser.Shell
import kotlin.system.exitProcess

object AppExitHelper {

    fun exitApplication(context: Context) {
        RootTouchClient.close()
        GlobalVars.serverActions?.close()
        GlobalVars.serverActions = null
        restoreDefaultInputMethod()
        stopDaemonProcess()
        stopAllServices(context)
        // Kill the current process
        exitProcess(0)
    }

    fun restoreDefaultInputMethod() {
        val defaultIme = GlobalVars.defaultInputMethod
        if (!defaultIme.isNullOrBlank()) {
            Shell.cmd("ime set $defaultIme").exec()
        }
    }

    private fun stopDaemonProcess() {
        try {
            // Use libsu to execute pgrep to find the PID(s) of the daemon script
            val pgrepResult = Shell.cmd("pgrep -f /data/local/tmp/sun_daemon.sh").exec()

            // Get all output lines (PIDs)
            val pids = pgrepResult.out

            if (pids.isNotEmpty()) {
                // Kill all found PIDs
                pids.forEach { pid ->
                    if (pid.isNotBlank()) {
                        Shell.cmd("kill -9 $pid").exec()
                    }
                }
            }
        } catch (e: Exception) {
            ShowMessage.record("Failed to stop the daemon process", Log.ERROR, throwable = e)
        }
    }

    private fun stopAllServices(context: Context) {
        try {
            context.stopService(Intent(context, UIWindowService::class.java))
            context.stopService(Intent(context, ControlWindowService::class.java))
            context.stopService(Intent(context, MessageBoxService::class.java))
            context.stopService(Intent(context, DaemonService::class.java))

            // Release screen capture resources
            ScreenCaptureManager.releaseAll()
        } catch (e: Exception) {
            ShowMessage.record("Failed to stop application services", Log.ERROR, throwable = e)
        }
    }
}
