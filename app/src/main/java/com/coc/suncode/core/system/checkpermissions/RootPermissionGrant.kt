package com.coc.suncode.core.system.checkpermissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.coc.suncode.core.system.inputmethod.SunInputMethodService
import com.coc.suncode.core.system.screencapture.RootProjectionPermission
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates permission operations through the host's private Root worker. */
internal object RootPermissionGrant {
    suspend fun grant(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            grantPermissions(context)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ShowMessage.record("Root 自动授权失败：${error.message}", throwable = error)
            false
        }
    }

    private suspend fun grantPermissions(context: Context): Boolean {
        val packageName = context.packageName
        require(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
        val user = Process.myUid() / 100_000
        val commands = mutableListOf(
            "cmd appops set --user $user $packageName SYSTEM_ALERT_WINDOW allow",
            "cmd appops set --user $user $packageName POST_NOTIFICATION allow"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            commands += "cmd appops set --user $user $packageName MANAGE_EXTERNAL_STORAGE allow"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            commands += "cmd appops set --user $user $packageName ACCESS_RESTRICTED_SETTINGS allow"
            commands += "pm clear-permission-flags --user $user $packageName android.permission.POST_NOTIFICATIONS user-set user-fixed"
            commands += "pm grant --user $user $packageName android.permission.POST_NOTIFICATIONS"
        } else {
            // Legacy storage grants are needed only on versions that still support them.
            commands += "pm grant --user $user $packageName android.permission.READ_EXTERNAL_STORAGE"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                commands += "pm grant --user $user $packageName android.permission.WRITE_EXTERNAL_STORAGE"
            }
        }
        // Enable our IME without changing the current keyboard; plugins switch it only when needed.
        val ime = ComponentName(context, SunInputMethodService::class.java)
        commands += "ime enable --user $user ${ime.flattenToShortString()}"
        val commandResult = ServerShell.exec(commands.joinToString(" && "))
        if (!commandResult.isSuccess) {
            ShowMessage.record("Root 权限命令返回失败：${commandResult.out.joinToString(" ").take(240)}")
            return false
        }

        val battery = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
        val accessibility = AccessibilityPermissionHelper.enableAccessibilityWithRoot(
            packageName,
            "com.coc.suncode.core.system.accessibility.MyAccessibilityService"
        )
        // PROJECT_MEDIA is an AppOp; MediaProjection still creates its own capture token.
        val projection = RootProjectionPermission.grant(context)
        val overlay = Settings.canDrawOverlays(context)
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val enabledImes = ServerShell.exec("settings --user $user get secure enabled_input_methods")
        val inputMethod = enabledImes.isSuccess && enabledImes.out.any { line ->
            line.trim().split(':').any { ComponentName.unflattenFromString(it.substringBefore(';')) == ime }
        }
        val granted = overlay && notifications && storage && battery && accessibility && projection && inputMethod
        if (!granted) {
            ShowMessage.record(
                "Root 权限检查未完成：悬浮窗=$overlay，通知=$notifications，存储=$storage，电池=$battery，无障碍=$accessibility，屏幕捕获=$projection，输入法=$inputMethod"
            )
        }
        if (granted) ShowMessage.record("Root 已自动授予全部运行权限（悬浮窗、通知、存储、电池、无障碍、屏幕捕获、输入法）")
        return granted
    }
}
