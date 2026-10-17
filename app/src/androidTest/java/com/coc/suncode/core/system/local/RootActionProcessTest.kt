package com.coc.suncode.core.system.local

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class RootActionProcessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before
    fun createPrivateFixtures() {
        directory = File(context.cacheDir, "local-actions-${System.nanoTime()}").apply {
            assertTrue(mkdirs())
        }
    }

    @After
    fun removePrivateFixtures() {
        // Root writes may preserve root ownership, so cleanup uses the same private root protocol.
        Worker().use { worker ->
            worker.shell("rm -rf ${quote(directory.path)}").expectSuccess()
        }
    }

    @Test
    fun fileOperationsPreserveUnicodeAndNeverInterpretPathsAsShell() {
        Worker().use { worker ->
            val path = File(directory, "子目录/a ' \$() `literal`\n.txt").path
            val copy = File(directory, "copy.txt").path
            val renamed = File(directory, "renamed.txt").path
            val text = "你好\nsecond line\t\"quote\"\\backslash\u0000"
            assertEquals("0", worker.request(JSONObject().put("actionType", "ping")).expectSuccess())
            assertEquals("false", worker.file("check_exists", path).expectSuccess())
            assertEquals("File created successfully", worker.file("create", path).expectSuccess())
            assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toString(),
                worker.file("write", path, "content" to text).expectSuccess())
            assertEquals(text, worker.file("read", path).expectSuccess())
            assertEquals("true", worker.file("copy", path, "destPath" to copy).expectSuccess())
            assertEquals(text, worker.file("read", copy).expectSuccess())
            assertEquals("true", worker.file("rename", copy, "newPath" to renamed).expectSuccess())
            assertEquals("false", worker.file("check_exists", copy).expectSuccess())
            assertEquals(text, worker.file("read", renamed).expectSuccess())
            worker.file("create", renamed).expectError("FILE_ERROR")
            worker.file("rename", path, "newPath" to renamed).expectError("FILE_ERROR")
            assertEquals("File deleted successfully", worker.file("delete", renamed).expectSuccess())
            worker.file("read", renamed).expectError("NOT_FOUND")
        }
        assertFalse(File(directory, "literal").exists())
    }

    @Test
    fun boundedFileFailuresLeaveThePreviousDestinationIntact() {
        Worker().use { worker ->
            val destination = File(directory, "configuration.json").path
            val original = "{\"preserved\":true}"
            worker.file("write", destination, "content" to original).expectSuccess()
            // /proc/self/mem is regular but unreadable at offset zero; copy must not truncate its target.
            worker.file("copy", "/proc/self/mem", "destPath" to destination).expectError("FILE_ERROR")
            assertEquals(original, worker.file("read", destination).expectSuccess())
            worker.file("write", destination, "content" to "x".repeat(8 * 1024 * 1024 + 1))
                .expectError("FILE_LIMIT")
            assertEquals(original, worker.file("read", destination).expectSuccess())

            val tooLarge = File(directory, "oversized.bin")
            tooLarge.outputStream().use { output ->
                val block = ByteArray(1024 * 1024)
                repeat(8) { output.write(block) }
                output.write(1)
            }
            worker.file("read", tooLarge.path).expectError("FILE_LIMIT")
            worker.file("read", directory.path).expectError("FILE_ERROR")
            worker.file("read", "relative/path").expectError("INVALID_REQUEST")
            worker.request(JSONObject().put("actionType", "unknown")).expectError("INVALID_REQUEST")
            assertEquals("0", worker.request(JSONObject().put("actionType", "ping")).expectSuccess())
            val temporaryCount = worker.shell("find ${quote(directory.path)} -name 'sun-local-*' | wc -l")
                .expectSuccess().trim()
            assertEquals("0", temporaryCount)
        }
    }

    @Test
    fun shellCombinesOutputBoundsExecutionAndKillsBackgroundChildren() {
        Worker().use { worker ->
            val output = worker.shell("printf 'hello\\n'; printf 'problem\\n' >&2; exit 7")
            assertEquals("hello\nproblem\n", output.expectSuccess())
            assertEquals(7, output.getInt("exitCode"))
            repeat(10) {
                assertEquals("0", worker.request(JSONObject().put("actionType", "ping")).expectSuccess())
            }
            worker.shell("yes x", 2_000).expectError("SHELL_OUTPUT_LIMIT")
            val marker = File(directory, "timed-out-child")
            worker.shell("sleep 30 & printf '%s' \"\$!\" > ${quote(marker.path)}; wait", 350)
                .expectError("SHELL_TIMEOUT")
            assertTrue("The timeout fixture must have launched a child", marker.exists())
            val child = marker.readText().trim().toInt()
            awaitChildStopped(worker, child)

            val started = SystemClock.elapsedRealtime()
            val background = worker.shell("sleep 30 & printf '%s' \"\$!\"; exit 0", 2_000)
                .expectSuccess().trim().toInt()
            assertTrue("A descendant retaining stdout must not keep the request open",
                SystemClock.elapsedRealtime() - started < 2_000)
            awaitChildStopped(worker, background)
        }
    }

    @Test
    fun hostPipeEofCancelsTheActiveShellAndItsDescendants() {
        val marker = File(directory, "disconnected-child")
        val worker = Worker()
        try {
            worker.send(JSONObject().put("actionType", "shell_action")
                .put("command", "sleep 30 & printf '%s' \"\$!\" > ${quote(marker.path)}; wait"))
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while ((!marker.exists() || marker.length() == 0L) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10)
            }
            assertTrue("The EOF fixture must have launched its child", marker.length() > 0)
            val child = marker.readText().trim().toInt()
            worker.disconnect()
            assertTrue("Worker must terminate promptly when its host pipe closes", worker.awaitExit())
            Worker().use { observer -> awaitChildStopped(observer, child) }
        } finally {
            worker.close()
        }
    }

    private fun awaitChildStopped(worker: Worker, pid: Int) {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        var state = ""
        do {
            // A killed, unreaped orphan can remain a zombie in emulator init; it cannot execute work.
            state = worker.shell("if [ -r /proc/$pid/stat ]; then cat /proc/$pid/stat; fi")
                .expectSuccess().trim()
            if (state.isEmpty() || state.substringAfterLast(')').trim().startsWith("Z ")) return
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("Shell child is still executing: $state", false)
    }

    private fun JSONObject.expectSuccess(): String {
        assertEquals(toString(), "success", getString("status"))
        return getString("data")
    }

    private fun JSONObject.expectError(code: String) {
        assertEquals(toString(), "error", getString("status"))
        assertEquals(toString(), code, getString("code"))
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private inner class Worker : Closeable {
        private val reads = Executors.newSingleThreadExecutor()
        private val process: Process
        private val input: BufferedReader
        private val output: BufferedWriter
        private var disconnected = false

        init {
            val apk = context.applicationInfo.sourceDir
            val launch = "CLASSPATH=${quote(apk)} exec app_process /system/bin ${RootActionProcess::class.java.name}"
            process = ProcessBuilder("su", "-c", launch).start()
            input = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            output = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
            Thread({
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { ShowMessage.record(it, tag = "RootActionProcessTest") }
                }
            }, "local-actions-test-stderr").apply { isDaemon = true; start() }
            try {
                assertEquals("SUN_ACTIONS_READY\t1", read())
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun file(action: String, path: String, vararg arguments: Pair<String, String>): JSONObject {
            val request = JSONObject().put("actionType", "file_action")
                .put("subAction", action).put("path", path)
            arguments.forEach { (key, value) -> request.put(key, value) }
            return request(request)
        }

        fun shell(command: String, timeoutMs: Long = 3_000): JSONObject = request(
            JSONObject().put("actionType", "shell_action")
                .put("command", command).put("timeoutMs", timeoutMs)
        )

        fun request(request: JSONObject): JSONObject {
            send(request)
            return JSONObject(read())
        }

        fun send(request: JSONObject) {
            assertFalse("No late response may pollute the next request", input.ready())
            output.write(request.toString())
            output.newLine()
            output.flush()
        }

        fun disconnect() {
            if (!disconnected) {
                disconnected = true
                output.close()
            }
        }

        fun awaitExit(): Boolean = process.waitFor(3, TimeUnit.SECONDS)

        private fun read(): String = reads.submit<String> {
            input.readLine() ?: error("Local root worker exited before replying")
        }.get(15, TimeUnit.SECONDS)

        override fun close() {
            runCatching { disconnect() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
            runCatching { input.close() }
            reads.shutdownNow()
        }
    }
}
