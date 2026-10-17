package com.coc.suncode.core.system.checkpermissions

import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PermissionManager {
    private val initializationMutex = Mutex()

    /**
     * Core permission check and grant logic
     */
    suspend fun checkAndGrantPermissions(
        context: Context,
        onStatusChange: (RootStatus) -> Unit
    ): RootStatus =
        withContext(Dispatchers.IO) {
            // Refresh a cached non-root shell before deciding that access was denied.
            if (RootShellProvider.getRootShell() == null) {
                return@withContext RootStatus.ROOT_DENIED
            }
            // 2. If Root is available, try to grant permissions silently
            val pkg = context.packageName
            Shell.cmd(
                "pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW",
                "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "pm grant $pkg android.permission.POST_NOTIFICATIONS",
                "pm grant $pkg android.permission.FOREGROUND_SERVICE",
            ).exec()
            // 2.5 Add battery optimization whitelist check
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)

            // 2.6 Enable accessibility service
            AccessibilityPermissionHelper.enableAccessibilityWithRoot(
                context.packageName,
                "com.coc.suncode.core.system.accessibility.MyAccessibilityService"
            )

            // 3. Check if permissions are actually granted (pm grant may not work for overlay on some systems)
            val hasOverlay = Settings.canDrawOverlays(context)
            val hasNotification = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!hasOverlay || !hasNotification) {
                return@withContext RootStatus.PERMISSION_DENIED
            }

            withContext(Dispatchers.Main) {
                onStatusChange(RootStatus.INITIALIZING_LOCAL)
            }

            // An upgraded installation may still have the detached legacy process alive.
            // The bracketed package prefix matches app_process command lines without matching this cleanup command.
            runCatching {
                val result = Shell.cmd("pkill -f '[c]om[.]coc[.]sunserver[.]ShellServer'").exec()
                if (!result.isSuccess && result.err.any { it.isNotBlank() }) {
                    ShowMessage("旧版进程清理失败：${result.err.joinToString(" ")}", isChecking = false)
                }
            }.onFailure { error ->
                ShowMessage.record("旧版进程清理失败：${error.message}", throwable = error)
            }

            if (!initializeLocalActions()) {
                return@withContext RootStatus.LOCAL_ERROR
            }

            return@withContext RootStatus.GRANTED
        }

    private suspend fun initializeLocalActions(): Boolean = initializationMutex.withLock {
        // Overlapping activity launches must share one ready backend rather than orphan a worker.
        if (GlobalVars.serverActions != null) return@withLock true
        val actions = try {
            ServerActions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShowMessage.record("内部配置初始化失败：${e.message}", throwable = e)
            return@withLock false
        }
        var retained = false
        try {
            if (!actions.awaitReady()) {
                ShowMessage("内部配置初始化失败，请检查日志后重试", isChecking = false)
                return@withLock false
            }
            // Publish only a ready backend so the overlay never observes partial startup.
            withContext(Dispatchers.Main) {
                GlobalVars.serverActions = actions
                retained = true
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShowMessage.record("内部配置加载失败：${e.message}", throwable = e)
            false
        } finally {
            // Screen disposal must also release a backend that has not been published.
            if (!retained) actions.close()
        }
    }
}
