package com.coc.suncode.core.qq

import com.coc.suncode.core.qq.QqToolBackend.Companion.quote
import com.coc.suncode.core.util.basic.ServerShell
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Stage only in private storage; all package installation is delegated to the host root pipe. */
internal class QqToolInstaller(
    private val cacheDirectory: File,
    private val openApk: () -> InputStream,
    private val execute: suspend (String) -> ServerShell.Result
) {
    companion object {
        const val APK_SHA256 = "d90d7c9b9116f8d5f1dfb27dc3ad3a9312640766380384f80601c8b8c38e0731"
    }

    suspend fun install(user: Int) {
        require(user >= 0)
        val staged = File.createTempFile("qq-authorization-", ".apk", cacheDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openApk().use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != APK_SHA256) {
                throw IOException("内置 QQ 授权工具校验失败，已取消安装")
            }
            // Root opens the private file; PackageManager consumes stdin without requiring file access.
            // Explicitly disable replacement, including packages installed concurrently or for another user.
            val command = "pm install -R --user $user -S ${staged.length()} - < ${quote(staged.absolutePath)}"
            val result = execute(command)
            if (!result.isSuccess || result.out.none { it.trim() == "Success" }) {
                val detail = result.out.joinToString(" ").trim().take(240)
                if (detail.contains("INSTALL_FAILED_ALREADY_EXISTS") ||
                    detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    detail.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
                    throw IOException("QQ 授权工具安装失败：设备上已有同包名应用，未覆盖或卸载；请检查 QQ 安装情况")
                }
                throw IOException("Root 安装 QQ 授权工具失败：" +
                    detail.ifEmpty { "请检查 Root 权限和系统安装策略" })
            }
        } finally {
            // This file contains only the public installer and is never retained as an authorization record.
            staged.delete()
        }
    }
}
