package com.coc.suncode.core.system.checkpermissions

import android.content.ComponentName
import android.os.Process
import com.coc.suncode.core.util.basic.ServerShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AccessibilityPermissionHelper {
    private val mutex = Mutex()

    /** Enable only our service for the app's Android user, preserving other enabled services. */
    suspend fun enableAccessibilityWithRoot(packageName: String, serviceClassName: String): Boolean = mutex.withLock {
        val component = ComponentName(packageName, serviceClassName)
        val user = Process.myUid() / 100_000
        val prefix = "settings --user $user"
        val current = ServerShell.exec("$prefix get secure enabled_accessibility_services")
        if (!current.isSuccess) return@withLock false
        val services = current.out.joinToString("").trim().split(':')
            .filter { it.isNotBlank() && it != "null" }.toMutableList()
        // Android accepts both flattened class names; compare components instead of substrings.
        if (services.none { ComponentName.unflattenFromString(it) == component }) {
            services += component.flattenToString()
        }
        val quoted = "'" + services.joinToString(":").replace("'", "'\\''") + "'"
        val result = ServerShell.exec(
            "$prefix put secure enabled_accessibility_services $quoted && " +
                "$prefix put secure accessibility_enabled 1 && " +
                "$prefix get secure enabled_accessibility_services && " +
                "$prefix get secure accessibility_enabled"
        )
        result.isSuccess && result.out.any { it.trim() == "1" } && result.out.any { line ->
            line.trim().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
