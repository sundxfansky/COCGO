package com.coc.suncode.core.qq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.data.websocket.ServerActions
import com.coc.suncode.core.util.basic.ServerShell
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device test: installs the bundled companion through the production Root pipe. */
@RunWith(AndroidJUnit4::class)
class QqToolInstallationTest {
    @Test fun installAndVerifyBundledToolWithoutStartingAuthorization() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "qq-install-test-${UUID.randomUUID()}")
        val actions = ServerActions(baseDir = directory.path + "/")
        val previous = GlobalVars.serverActions
        try {
            assertTrue(actions.awaitReady())
            GlobalVars.serverActions = actions
            val commands = mutableListOf<String>()
            val installer = QqToolInstaller(
                context.cacheDir,
                { context.assets.open("qq/authorization-tool-v1.4.apk") },
                { command -> commands += command; ServerShell.exec(command) }
            )
            // Never uninstall or replace a package to force this test's missing-package branch.
            val installed = ServerShell.exec("pm path --user ${Process.myUid() / 100_000} ${QqToolBackend.PACKAGE}")
            if (installed.out.none { it.startsWith("package:") }) {
                installer.install(Process.myUid() / 100_000)
                assertEquals(1, commands.size)
                val remote = Regex("/data/local/tmp/sun-qq-[a-zA-Z0-9-]+\\.apk").find(commands.single())!!.value
                assertTrue(ServerShell.exec("[ ! -e '$remote' ]").isSuccess)
            }
            // Validate identity before changing permissions on a package shared with official QQ.
            QqToolBackend(context).verifyTool()
            // Simulate a clean install so verifyTool proves that Root repairs grants before launch.
            val permissions = mutableListOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += listOf(Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES)
            }
            val revoke = permissions.joinToString(" && ") { permission ->
                "pm revoke --user ${Process.myUid() / 100_000} ${QqToolBackend.PACKAGE} $permission"
            }
            assertTrue(ServerShell.exec(revoke).isSuccess)
            // This validates the real installed signature and exported entry without resetting game data.
            QqToolBackend(context).verifyTool()
            QqToolBackend(context).verifyTool()
            // Verification must prepare runtime grants without opening a permission activity.
            assertEquals(PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE, QqToolBackend.PACKAGE
            ))
            assertEquals(PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE, QqToolBackend.PACKAGE
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (permission in listOf(Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES)) {
                    assertEquals(PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission(
                        permission, QqToolBackend.PACKAGE
                    ))
                }
            }
        } finally {
            actions.sendActionSync(mapOf("actionType" to "file_action", "subAction" to "delete", "path" to directory.path))
            GlobalVars.serverActions = previous
            actions.close()
        }
    }
}
