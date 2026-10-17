package com.coc.suncode.core.system.daemon

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.coc.suncode.MainActivity
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.system.checkpermissions.RootShellProvider
import com.coc.suncode.core.ui.floatingwindows.NotificationHelper
import com.coc.suncode.core.util.basic.ShowMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Read the local startup setting when the device boots.
class DaemonService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverActions: ServerActions? = null
    private var startJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val notification: Notification = NotificationHelper.createNotification(this)
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startJob?.isActive == true) return START_NOT_STICKY
        startJob = scope.launch {
            ShowMessage.record("读取本地开机启动配置")
            try {
                if (RootShellProvider.getRootShell() == null) {
                    ShowMessage.record("开机启动检查未获得 Root 权限")
                    return@launch
                }
                // This short-lived reader must not take ownership of the UI's shared backend.
                val actions = ServerActions()
                serverActions = actions
                if (!actions.awaitReady()) {
                    ShowMessage.record("开机启动配置读取失败")
                    return@launch
                }
                val autoStart = runCatching { actions.getValue("auto_start")?.toInt() }
                    .getOrNull() ?: 0
                if (autoStart == 1) {
                    val activityIntent = Intent(this@DaemonService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(activityIntent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShowMessage.record("开机启动检查失败：${e.message}", throwable = e)
            } finally {
                serverActions?.close()
                serverActions = null
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A completed or stopped boot check must release its backend and coroutine scope.
        scope.cancel()
        serverActions?.close()
        serverActions = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
