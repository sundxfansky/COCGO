package com.coc.suncode.core.system.daemon

import android.content.Context
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.topjohnwu.superuser.Shell
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object ServerManager {

    @Synchronized
    fun startServer(context: Context): Boolean {
        return try {
            val authToken = RootServerAuth.initialize(context)
            val serverFile = File(context.filesDir, "server.apk")
            val serverUpdated = ServerApkInstaller.install(serverFile) {
                context.assets.open("server.apk")
            }
            GlobalVars.serverPath = serverFile.absolutePath

            // Reuse only the current asset; an app update must restart an outdated server.
            if (!serverUpdated && isServerAvailable()) return true

            // Kill any stale ShellServer processes to free port 6839 before starting a new one
            Shell.cmd("pkill -f com.coc.sunserver.ShellServer").exec()

            // Start the server in a detached session so it survives parent process termination
            val result = Shell.cmd("setsid sh -c 'export CLASSPATH=${GlobalVars.serverPath} SUN_SERVER_TOKEN=$authToken; exec app_process /system/bin com.coc.sunserver.ShellServer' < /dev/null > /dev/null 2>&1 &")
                .exec()
            if (!result.isSuccess) {
                ShowMessage("Root 服务启动命令失败：${result.err.joinToString("\n")}", isChecking = false)
            }
            result.isSuccess
        } catch (e: Exception) {
            ShowMessage("Root 服务启动失败：${e.message}", isChecking = false)
            false
        }
    }

    private fun isServerAvailable(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 6839), 200)
            }
            true
        }.getOrDefault(false)
    }
}
