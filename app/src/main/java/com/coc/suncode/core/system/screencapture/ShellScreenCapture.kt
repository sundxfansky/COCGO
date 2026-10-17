package com.coc.suncode.core.system.screencapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.coc.suncode.core.util.fileactions.LogHelper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persistent root shell screen capture.
 * Maintains a long-lived `su` process and uses `screencap -p` to read PNG data
 * directly from stdout by parsing PNG chunks, avoiding all file I/O.
 */
object ShellScreenCapture {

    @Volatile private var process: Process? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "capture-timeout").apply { isDaemon = true } }
    private var shellStdin: OutputStream? = null
    private var shellStdout: BufferedInputStream? = null

    private val connectionLock = Any()
    private val captureMutex = Mutex()

    @Volatile private var captureDeadline = 0L

    private const val INIT_MARKER = "__SHELL_READY__"
    private const val STREAM_BUFFER_SIZE = 1024 * 1024 // 1MB read buffer

    /**
     * Ensure the persistent su shell is alive. Recreate if the process is dead.
     */
    private fun ensureConnection() {
        synchronized(connectionLock) {
            if (process != null && isProcessAlive(process!!)) return
            closeConnectionLocked()

            try {
                val pb = ProcessBuilder("su")
                // Drain stderr independently; mixing it into stdout corrupts PNG framing.
                pb.redirectErrorStream(false)
                val proc = pb.start()
                process = proc
                Thread({ runCatching { proc.errorStream.use { input ->
                    val discard = ByteArray(4096)
                    while (input.read(discard) >= 0) { }
                } } }, "capture-stderr").apply { isDaemon = true; start() }
                shellStdin = proc.outputStream
                shellStdout = BufferedInputStream(proc.inputStream, STREAM_BUFFER_SIZE)
                LogHelper.showDebugInfo("ShellScreenCapture: started root shell")

                // Drain any initial output (e.g. Magisk greeting) by sending a marker
                val deadline = watchdog.schedule({ proc.destroy() }, 5, TimeUnit.SECONDS)
                try { drainUntilMarker() } finally { deadline.cancel(false) }

            } catch (e: Exception) {
                LogHelper.showDebugInfo("ShellScreenCapture: root shell startup failed: ${e.message}")
                closeConnectionLocked()
                throw e
            }
        }
    }

    /**
     * Send a known echo marker and discard all bytes until the marker line appears.
     * This ensures the shell is ready and any startup messages are consumed.
     */
    private fun drainUntilMarker() {
        val stdin = shellStdin ?: return
        val stdout = shellStdout ?: return

        stdin.write("echo $INIT_MARKER\n".toByteArray())
        stdin.flush()

        val lineBuffer = StringBuilder()
        while (true) {
            val b = readExactly(stdout, 1)[0].toInt() and 255
            if (b == '\n'.code) {
                if (lineBuffer.toString().trim() == INIT_MARKER) return
                lineBuffer.clear()
            } else {
                lineBuffer.append(b.toChar())
            }
        }
    }

    private fun closeConnectionLocked() {
        // Kill first so a blocked read releases its stream monitor before close waits for it.
        runCatching { process?.destroy() }
        runCatching { shellStdin?.close() }
        runCatching { shellStdout?.close() }
        process = null
        shellStdin = null
        shellStdout = null
    }

    /**
     * Release the persistent shell connection and all resources.
     */
    fun release() {
        synchronized(connectionLock) {
            closeConnectionLocked()
        }
    }

    /**
     * Capture the screen via persistent root shell.
     * Thread-safe; only one capture runs at a time. Retries once on connection failure.
     */
    suspend fun capture(asBitmap: Boolean): Any? = captureMutex.withLock {
        coroutineScope {
            suspendCancellableCoroutine { continuation ->
                // Cancellation kills the current process without waiting on the connection lock.
                continuation.invokeOnCancellation { process?.destroy() }
                launch(Dispatchers.IO) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val result = captureInternal(asBitmap)
                        continuation.resume(result) { _, abandoned, _ ->
                            if (abandoned is Bitmap) abandoned.recycle()
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    private suspend fun captureInternal(asBitmap: Boolean): Any? {
        // Retry once: first attempt may fail if the connection died between captures
        repeat(2) { attempt ->
            try {
                currentCoroutineContext().ensureActive()
                captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                ensureConnection()
                val stdin = shellStdin ?: return null
                val stdout = shellStdout ?: return null

                val currentProcess = process
                val deadline = watchdog.schedule({ currentProcess?.destroy() }, 5, TimeUnit.SECONDS)
                val result = try { captureViaPng(stdin, stdout, asBitmap) } finally { deadline.cancel(false) }

                if (result != null) {
                    if (attempt > 0) LogHelper.showDebugInfo("ShellScreenCapture: capture recovered after reconnect")
                    return result
                }

                LogHelper.showDebugInfo("ShellScreenCapture: screencap returned no image")
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.showDebugInfo("ShellScreenCapture: capture attempt ${attempt + 1} failed: ${e.message}")
                synchronized(connectionLock) { closeConnectionLocked() }
                currentCoroutineContext().ensureActive()
                if (attempt > 0) return null
            }
        }
        return null
    }

    // ======================== PNG screencap ========================

    /**
     * Capture using `screencap -p` and parse PNG chunks from the stream.
     * PNG structure: 8-byte signature, then chunks until IEND.
     * Each chunk: 4-byte length (big-endian) + 4-byte type + length data + 4-byte CRC.
     */
    private fun captureViaPng(
        stdin: OutputStream,
        stdout: InputStream,
        asBitmap: Boolean
    ): Any? {
        stdin.write("screencap -p\n".toByteArray())
        stdin.flush()

        val pngBytes = readPngFromStream(stdout)

        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: return null

        if (asBitmap) return bitmap

        // Convert Bitmap to CaptureResult (raw RGBA buffer)
        val width = bitmap.width
        val height = bitmap.height
        val pixelStride = 4
        val rowStride = width * pixelStride
        val buffer = ByteBuffer.allocateDirect(height * rowStride)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        bitmap.recycle()

        return ScreenCaptureManager.CaptureResult(buffer, width, height, pixelStride, rowStride)
    }

    /**
     * Read a complete PNG from the stream by parsing its chunk structure.
     * Stops after the IEND chunk, leaving the stream positioned for the next command.
     */
    private fun readPngFromStream(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()

        // 8-byte PNG signature
        val signature = readExactly(stream, 8)
        output.write(signature)

        // Validate PNG magic bytes
        val expectedSig = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (!signature.contentEquals(expectedSig)) {
            throw IOException(
                "Invalid PNG signature: ${signature.joinToString(" ") { "%02X".format(it) }}"
            )
        }

        // Read chunks until IEND
        while (true) {
            // Chunk header: 4-byte data length + 4-byte type
            val chunkHeader = readExactly(stream, 8)
            output.write(chunkHeader)
            check(output.size() <= 64 * 1024 * 1024) { "PNG exceeds capture limit" }

            val length = ByteBuffer.wrap(chunkHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            val type = String(chunkHeader, 4, 4, Charsets.US_ASCII)

            if (length !in 0..50_000_000) {
                throw IOException("PNG chunk '$type' has unreasonable length: $length")
            }

            // Bound aggregate memory before allocating any chunk payload.
            if (output.size().toLong() + length + 4 > 64L * 1024 * 1024) throw IOException("PNG exceeds capture limit")
            // Read chunk data
            if (length > 0) {
                val data = readExactly(stream, length)
                output.write(data)
            }

            // Read 4-byte CRC
            val crc = readExactly(stream, 4)
            output.write(crc)

            if (type == "IEND") break
        }

        return output.toByteArray()
    }

    // ======================== Utility ========================

    /**
     * API 24-compatible check for whether a process is still running.
     * Uses exitValue() which throws IllegalThreadStateException if the process has not yet terminated.
     */
    private fun isProcessAlive(proc: Process): Boolean {
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Read exactly [size] bytes from [stream], blocking until all bytes are received.
     * Throws IOException if the stream closes before all bytes are read.
     */
    private fun readExactly(stream: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            // Poll the pipe rather than entering an unbounded native read held by a surviving child.
            if (System.nanoTime() >= captureDeadline) throw IOException("Screen capture timed out")
            val available = stream.available()
            if (available == 0) {
                if (process?.let { isProcessAlive(it) } != true) throw IOException("Screen capture process closed")
                Thread.sleep(5)
                continue
            }
            val read = stream.read(buf, offset, minOf(size - offset, available))
            if (read == -1) throw IOException("Stream closed prematurely, read $offset/$size bytes")
            offset += read
        }
        return buf
    }
}
