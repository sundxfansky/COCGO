package com.coc.suncode.core.system.input

import android.content.pm.PackageManager
import com.coc.suncode.core.util.basic.ShowMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TouchInputException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Private pipe to a host-owned privileged process; touch input never uses the file server. */
internal object RootTouchClient {
    private const val ENTRY_POINT = "com.coc.suncode.core.system.input.RootTouchProcess"
    private val mutex = Mutex()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "touch-timeout").apply { isDaemon = true }
    }
    @Volatile private var connection: Connection? = null
    @Volatile private var closingConnection: Connection? = null

    suspend fun down(packageName: String, id: Int, x: Float, y: Float) = exchange { operation ->
        val current = connection ?: connect(operation)
        if (current.targetPackage != packageName) {
            val context = ShowMessage.getContext() ?: throw IOException("应用尚未初始化")
            val uid = try {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            } catch (error: PackageManager.NameNotFoundException) {
                throw TouchInputException("未找到目标应用：$packageName", error)
            }
            current.command("TARGET\t$packageName\t$uid", operation)
            current.targetPackage = packageName
        }
        current.command("DOWN\t$id\t$x\t$y", operation)
    }

    suspend fun move(id: Int, x: Float, y: Float) = exchange { operation ->
        val current = connection ?: throw TouchInputException("触摸连接已断开，请重新运行辅助")
        current.command("MOVE\t$id\t$x\t$y", operation)
    }

    suspend fun up(id: Int) = exchange { operation ->
        // A dead worker has already released its gesture on EOF; never start a new one for UP.
        connection?.command("UP\t$id", operation)
        Unit
    }

    suspend fun cancel() = exchange { operation ->
        connection?.command("CANCEL", operation)
        Unit
    }

    fun close() {
        val previous = connection
        if (previous != null) closingConnection = previous
        connection = null
        previous?.stop()
    }

    private suspend fun <T> exchange(block: (CoroutineContext) -> T): T = mutex.withLock {
        coroutineScope {
            suspendCancellableCoroutine { continuation ->
                // Destroying the process unblocks pipe reads when the bot is stopped.
                continuation.invokeOnCancellation { close() }
                launch(Dispatchers.IO) {
                    try {
                        coroutineContext.ensureActive()
                        val result = block(coroutineContext)
                        continuation.resume(result)
                    } catch (error: Exception) {
                        val abandoned = connection
                        connection = null
                        if (abandoned != null) {
                            closingConnection = abandoned
                            abandoned.stop()
                        }
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (error is CancellationException || error is TouchInputException) error
                                else TouchInputException("Root 触摸失败：${error.message}", error)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun connect(operation: CoroutineContext): Connection {
        // A replacement must not race the old worker's final ACTION_CANCEL.
        closingConnection?.let { previous ->
            previous.awaitStopped()
            if (closingConnection === previous) closingConnection = null
        }
        operation.ensureActive()
        val context = ShowMessage.getContext() ?: throw IOException("应用尚未初始化")
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=${shellQuote(apk)} exec app_process /system/bin $ENTRY_POINT"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        val current = Connection(process)
        connection = current
        // Cancellation may have arrived while su was starting, before the process was published.
        operation.ensureActive()
        // Runtime diagnostics must not corrupt the reply stream or fill the stderr pipe.
        Thread({
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        ShowMessage.record(line.take(2000), tag = "RootTouchProcess")
                    }
                }
            }
        }, "touch-stderr").apply { isDaemon = true; start() }
        current.withDeadline(10_000) {
            var ready = false
            repeat(32) {
                operation.ensureActive()
                val line = current.reader.readLine() ?: throw IOException("Root 输入进程已退出，请检查 Root 授权")
                if (line.startsWith("SUN_TOUCH_READY\t1\t")) {
                    ready = true
                    return@withDeadline
                }
                if (line.startsWith("ERR\t")) throw TouchInputException(line.substringAfter('\t'))
            }
            check(ready) { "Root touch handshake failed" }
        }
        ShowMessage.record("已连接本地 Root 输入进程，触摸不再经过 sunserver")
        return current
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private class Connection(val process: Process) {
        val reader: BufferedReader = process.inputStream.bufferedReader()
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        private val stopping = AtomicBoolean()
        var targetPackage: String? = null

        fun command(command: String, operation: CoroutineContext) = withDeadline(5_000) {
            operation.ensureActive()
            writer.write(command)
            writer.newLine()
            writer.flush()
            when (val reply = reader.readLine()) {
                "OK" -> Unit
                null -> throw IOException("Root 输入进程已断开")
                else -> throw TouchInputException(reply.removePrefix("ERR\t"))
            }
        }

        fun <T> withDeadline(timeoutMillis: Long, block: () -> T): T {
            val deadline = watchdog.schedule({ stop() }, timeoutMillis, TimeUnit.MILLISECONDS)
            return try { block() } finally { deadline.cancel(false) }
        }

        fun stop() {
            if (!stopping.compareAndSet(false, true)) return
            // Give the worker EOF first so its finally block can release an active gesture.
            runCatching { process.outputStream.close() }
            watchdog.schedule({ runCatching { process.destroy() } }, 6, TimeUnit.SECONDS)
        }

        fun awaitStopped() {
            stop()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
            while (true) {
                try {
                    process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    if (System.nanoTime() >= deadline) {
                        throw TouchInputException("旧输入进程未能退出，请重新启动辅助")
                    }
                    Thread.sleep(25)
                }
            }
            runCatching { reader.close() }
        }
    }
}
