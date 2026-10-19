package com.coc.suncode.core.system.checkpermissions

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.ServerShell
import kotlinx.coroutines.CancellationException

object BatteryOptimizationHelper {
    /**
     * Check whether my app is in the battery optimization whitelist.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Add the package to the device-idle whitelist without opening a settings screen. */
    suspend fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val packageName = context.packageName
        if (isIgnoringBatteryOptimizations(context)) return true
        return try {
            // Root can update device-idle state directly; no user-facing settings activity is needed.
            val result = ServerShell.exec(
                "cmd deviceidle whitelist +$packageName || dumpsys deviceidle whitelist +$packageName"
            )
            val granted = isIgnoringBatteryOptimizations(context)
            if (!granted) {
                ShowMessage.record(
                    "Root 未能加入电池优化白名单：${result.out.joinToString(" ").take(240)}",
                    Log.ERROR
                )
            }
            granted
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ShowMessage.record("Root 设置电池优化白名单失败", Log.ERROR, throwable = error)
            false
        }
    }
}
