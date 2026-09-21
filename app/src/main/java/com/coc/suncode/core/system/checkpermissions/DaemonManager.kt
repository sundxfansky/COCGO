package com.coc.suncode.core.system.checkpermissions

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import java.io.File
import com.coc.suncode.core.util.basic.ShowMessage
import kotlinx.coroutines.CancellationException


suspend fun setupAndRunDaemon(context: Context) {
    val scriptPath = File(context.filesDir, "sun_daemon.sh").absolutePath
    val pkg = context.packageName

    val scriptContent = listOf(
        "#!/system/bin/sh",
        "",
        "while true; do",
        "    if ! pidof $pkg > /dev/null; then",
        "        am start -n $pkg/.MainActivity >>/dev/null 2>&1",
        "    fi",
        "    sleep 10",
        "done"
    )

    try {
        // Private storage is available before the WebSocket service starts.
        File(scriptPath).writeText(scriptContent.joinToString("\n") + "\n")

        // 4. Identify and kill existing daemon processes
        // We search for the script path in the process list, excluding the 'grep' command itself
        val existingPids = Shell.cmd("ps -ef | grep '$scriptPath' | grep -v grep")
            .exec().out.mapNotNull { line ->
                // Extract the PID (usually the second column in ps -ef)
                line.trim().split("\\s+".toRegex()).getOrNull(1)
            }

        if (existingPids.isNotEmpty()) {
            for (pid in existingPids) {
                Shell.cmd("kill -9 $pid").exec()
            }
            // Small delay to ensure the system releases the process resources
            delay(300)
        }

        // 5. Start the daemon process in the background
        // Use nohup and setsid for enhanced fault tolerance
        val cmdStart = "nohup sh $scriptPath > /dev/null 2>&1 &"
        Shell.cmd(cmdStart).exec()
        // Start one daemon only; starting both nohup and setsid duplicated work.

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ShowMessage("守护进程启动失败: ${e.message}", isChecking = false)
    }
}
