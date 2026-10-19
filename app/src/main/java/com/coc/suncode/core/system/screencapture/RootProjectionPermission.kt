package com.coc.suncode.core.system.screencapture

import android.content.Context
import android.os.Process
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Grant capture AppOps through the existing host Root pipe before requesting a projection token. */
object RootProjectionPermission {
    suspend fun grant(context: Context): Boolean = withContext(Dispatchers.IO) {
        val user = Process.myUid() / 100_000
        val packageName = context.packageName
        require(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
        try {
            // MediaProjection still creates its own token and foreground service; only consent is pre-granted.
            val result = ServerShell.exec(
                "cmd appops set --user $user $packageName PROJECT_MEDIA allow && " +
                    "cmd appops get --user $user $packageName PROJECT_MEDIA"
            )
            val granted = result.isSuccess && result.out.any {
                Regex("^\\s*PROJECT_MEDIA:\\s*allow(?:[;\\s].*)?$").matches(it)
            }
            if (granted) ShowMessage.record("Root 已自动授予屏幕捕获权限")
            else ShowMessage.record("Root 未能授予屏幕捕获权限：${result.out.joinToString(" ").take(240)}")
            granted
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keep failures visible without opening a manual system permission flow.
            ShowMessage.record("Root 屏幕捕获授权失败：${error.message}")
            false
        }
    }
}
