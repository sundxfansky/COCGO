package com.coc.suncode.core.system.checkpermissions

import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import java.io.IOException

internal object RootShellProvider {
    fun getRootShell(getShell: () -> Shell = { Shell.getShell() }): Shell? {
        try {
            // libsu caches fallback shells; close them before retrying authorization once.
            repeat(2) {
                val shell = getShell()
                if (shell.isAlive && shell.isRoot) {
                    return shell
                }
                shell.close()
            }
        } catch (_: NoShellException) {
            // Leave the screen retryable when shell initialization fails.
        } catch (_: IOException) {
            // A failed shell cleanup must not crash the permission screen.
        }
        return null
    }
}
