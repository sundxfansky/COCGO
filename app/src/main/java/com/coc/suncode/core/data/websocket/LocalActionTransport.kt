package com.coc.suncode.core.data.websocket

import com.coc.suncode.core.util.basic.ShowMessage
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Retains the plugin transport ABI while exchanging commands over private pipes, without sockets. */
internal class LocalActionTransport : ServerTransport {
    private companion object {
        const val ENTRY = "com.coc.suncode.core.system.local.RootActionProcess"
        const val MAX_LINE = 64 * 1024 * 1024
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "local-root-watchdog").apply { isDaemon = true }
        }
    }

    private val gson = Gson()
    private val lock = Any()
    @Volatile private var session: Session? = null
    private var retiring: Session? = null

    override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        val next = Session(onOpen, onMessage, onFailure)
        val previous = synchronized(lock) {
            (session ?: retiring).also { session = next; retiring = null }
        }
        previous?.stop()
        Thread({ next.run(previous) }, "local-root-actions").apply { isDaemon = true; start() }
    }

    override fun sendAction(action: Any): Boolean {
        val current = session ?: return false
        val command = gson.toJson(action)
        require(command.length <= MAX_LINE) { "本地请求超出大小限制" }
        return !current.stopped.get() && current.commands.offer(command)
    }

    override fun close() {
        val previous = synchronized(lock) {
            session.also {
                if (it != null) retiring = it
                session = null
            }
        }
        previous?.stop()
    }

    private inner class Session(
        val onOpen: () -> Unit,
        val onMessage: (String) -> Unit,
        val onFailure: (Throwable) -> Unit
    ) {
        val stopped = AtomicBoolean()
        val commands = LinkedBlockingQueue<String>(64)
        private val terminated = CountDownLatch(1)
        @Volatile private var process: Process? = null
        @Volatile private var shutdownError: IOException? = null

        private fun isCurrent() = !stopped.get() && session === this

        fun run(previous: Session?) {
            try {
                // Do not let an abandoned mutation race the replacement's configuration reads.
                try {
                    previous?.awaitStopped()
                } catch (error: IOException) {
                    shutdownError = error
                    throw error
                }
                if (!isCurrent()) return
                val context = ShowMessage.getContext() ?: throw IOException("应用尚未初始化")
                val apk = context.applicationInfo.sourceDir
                val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
                // Only the trusted APK path enters the launcher shell; requests stay in JSON pipes.
                val child = ProcessBuilder(
                    "su", "-c", "CLASSPATH=$quotedApk exec app_process /system/bin $ENTRY"
                ).redirectErrorStream(false).start()
                process = child
                if (!isCurrent()) {
                    child.outputStream.close()
                    child.destroy()
                    return
                }
                Thread({
                    runCatching {
                        child.errorStream.bufferedReader().use { errors ->
                            errors.forEachLine { ShowMessage.record(it.take(2000), tag = "RootActionProcess") }
                        }
                    }
                }, "local-root-stderr").apply { isDaemon = true; start() }
                val deadline = watchdog.schedule({ stop() }, 10, TimeUnit.SECONDS)
                val reader = child.inputStream.bufferedReader()
                try {
                    var ready = false
                    for (attempt in 0 until 32) {
                        val line = readLine(reader) ?: throw IOException("本地 Root 进程已退出，请检查 Root 授权")
                        if (line == "SUN_ACTIONS_READY\t1") {
                            ready = true
                            break
                        }
                        if (line.startsWith("{")) throw IOException(line.take(2000))
                    }
                    check(ready) { "本地 Root 进程握手失败" }
                } finally {
                    deadline.cancel(false)
                }
                if (!isCurrent()) return
                ShowMessage.record("本地 Root 文件和命令功能已就绪")
                onOpen()
                val writer = child.outputStream.bufferedWriter()
                while (isCurrent()) {
                    val command = commands.poll(1, TimeUnit.SECONDS) ?: continue
                    writer.write(command)
                    writer.newLine()
                    writer.flush()
                    val reply = readLine(reader) ?: throw IOException("本地 Root 进程连接已断开")
                    // A cancelled connection can never donate a late response to its replacement.
                    if (isCurrent()) onMessage(reply)
                }
            } catch (error: Exception) {
                if (isCurrent()) onFailure(error)
            } finally {
                stop()
                try {
                    process?.let { child ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                        while (true) {
                            try {
                                child.exitValue()
                                break
                            } catch (_: IllegalThreadStateException) {
                                if (System.nanoTime() >= deadline) {
                                    throw IOException("旧本地 Root 进程未能退出，请重启辅助")
                                }
                                Thread.sleep(10)
                            }
                        }
                        runCatching { child.inputStream.close() }
                        runCatching { child.errorStream.close() }
                    }
                } catch (error: Exception) {
                    shutdownError = IOException("本地 Root 进程清理失败", error)
                    ShowMessage.record(shutdownError!!.message.orEmpty(), throwable = error)
                } finally {
                    terminated.countDown()
                }
            }
        }

        private fun awaitStopped() {
            if (!terminated.await(8, TimeUnit.SECONDS)) throw IOException("等待旧本地 Root 进程退出超时")
            shutdownError?.let { throw it }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            commands.clear()
            val child = process ?: return
            // EOF lets the root worker terminate its active shell and descendants before exiting.
            watchdog.schedule({ runCatching { child.destroy() } }, 2, TimeUnit.SECONDS)
            Thread({ runCatching { child.outputStream.close() } }, "local-root-close")
                .apply { isDaemon = true; start() }
        }
    }

    private fun readLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value == -1) return if (line.isEmpty()) null else throw IOException("本地响应不完整")
            if (value == '\n'.code) return line.toString()
            if (line.length >= MAX_LINE) throw IOException("本地响应超出大小限制")
            line.append(value.toChar())
        }
    }
}
