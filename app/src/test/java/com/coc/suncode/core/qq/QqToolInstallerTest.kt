package com.coc.suncode.core.qq

import com.coc.suncode.core.util.basic.ServerShell
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QqToolInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bundledApk = File("src/main/assets/qq/authorization-tool-v1.4.apk")

    @Test fun validBundleInstallsForCurrentUserWithoutReplacingPackagesAndCleansUp() = runBlocking {
        var calls = 0
        val cache = temporary.newFolder("private cache's directory")
        QqToolInstaller(cache, { bundledApk.inputStream() }) { command ->
            calls++
            val staged = cache.listFiles()!!.single()
            assertTrue(staged.readBytes().contentEquals(bundledApk.readBytes()))
            assertTrue(command.contains("pm install -R --user 10 -S ${bundledApk.length()} '/data/local/tmp/sun-qq-"))
            assertTrue(command.contains("cp '"))
            assertTrue(command.contains("rm -f -- '/data/local/tmp/sun-qq-"))
            assertTrue(command.contains("private cache"))
            ServerShell.Result(0, listOf("Success"))
        }.install(10)
        assertEquals(1, calls)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun corruptedBundleNeverReachesRoot() = runBlocking {
        val cache = temporary.newFolder()
        val error = runCatching {
            QqToolInstaller(cache, { byteArrayOf(1, 2, 3).inputStream() }) {
                error("Invalid APK reached root")
            }.install(0)
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("校验失败"))
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun packageConflictFailsWithoutRetryingOrRemovingAnything() = runBlocking {
        var calls = 0
        val cache = temporary.newFolder()
        val error = runCatching {
            QqToolInstaller(cache, { bundledApk.inputStream() }) {
                calls++
                ServerShell.Result(1, listOf("Failure [INSTALL_FAILED_ALREADY_EXISTS]"))
            }.install(0)
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("未覆盖或卸载"))
        assertEquals(1, calls)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun exitCodeAndSuccessOutputMustBothConfirmInstallation() = runBlocking {
        for (result in listOf(ServerShell.Result(0, emptyList()), ServerShell.Result(1, listOf("Success")))) {
            val cache = temporary.newFolder()
            val error = runCatching {
                QqToolInstaller(cache, { bundledApk.inputStream() }) { result }.install(0)
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun rootFailureAndCancellationCleanUpAndPropagate() = runBlocking {
        for (failure in listOf(IOException("Root unavailable"), CancellationException("Cancelled"))) {
            val cache = temporary.newFolder()
            val error = runCatching {
                QqToolInstaller(cache, { bundledApk.inputStream() }) { throw failure }.install(0)
            }.exceptionOrNull()
            assertTrue(error === failure)
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }
}
