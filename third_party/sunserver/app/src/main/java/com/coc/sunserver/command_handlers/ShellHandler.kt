package com.coc.sunserver.command_handlers

import com.google.gson.JsonObject
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Legacy command compatibility; all external file access now runs inside the authenticated server. */
object ShellHandler {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "shell-watchdog").apply { isDaemon = true }
    }

    fun handle(request: JsonObject, timeoutSeconds: Long = 25): String {
        val command = request.get("command")?.asString ?: error("Missing command")
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val timeout = watchdog.schedule({ process.destroy() }, timeoutSeconds, TimeUnit.SECONDS)
        return try {
            val output = process.inputStream.use { stream ->
                val bytes = stream.readBytesLimited(1024 * 1024, process, timeoutSeconds)
                String(bytes, Charsets.UTF_8)
            }
            JsonObject().apply {
                addProperty("status", "success")
                addProperty("exitCode", process.waitFor())
                addProperty("data", output)
            }.toString()
        } finally {
            timeout.cancel(false)
            process.destroy()
        }
    }

    private fun InputStream.readBytesLimited(limit: Int, process: Process, timeoutSeconds: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            if (System.nanoTime() >= deadline) throw IOException("Command timed out")
            val ready = available()
            if (ready == 0) {
                val exited = try { process.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                if (exited) break
                Thread.sleep(5)
                continue
            }
            val count = read(buffer, 0, minOf(buffer.size, ready))
            if (count < 0) break
            check(output.size() + count <= limit) { "Command output exceeded limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
