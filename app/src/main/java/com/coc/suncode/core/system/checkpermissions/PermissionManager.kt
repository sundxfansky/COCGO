package com.coc.suncode.core.system.checkpermissions

import android.content.Context
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.ServerShell
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
            // Bootstrap the private Root pipe before any permission or settings commands.
            withContext(Dispatchers.Main) {
                onStatusChange(RootStatus.INITIALIZING_LOCAL)
            }
            if (!initializeLocalActions()) return@withContext RootStatus.LOCAL_ERROR
            if (!RootPermissionGrant.grant(context)) return@withContext RootStatus.PERMISSION_DENIED

            // An upgraded installation may still have the detached legacy process alive.
            // The bracketed package prefix matches app_process command lines without matching this cleanup command.
            runCatching {
                val result = ServerShell.exec("pkill -f '[c]om[.]coc[.]sunserver[.]ShellServer'")
                if (!result.isSuccess && result.out.any { it.isNotBlank() }) {
                    ShowMessage("旧版进程清理失败：${result.out.joinToString(" ")}", isChecking = false)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                ShowMessage.record("旧版进程清理失败：${error.message}", throwable = error)
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
