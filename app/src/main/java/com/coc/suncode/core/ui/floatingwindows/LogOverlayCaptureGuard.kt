package com.coc.suncode.core.ui.floatingwindows

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Keep screen recognition independent of the visible host-owned log window. */
object LogOverlayCaptureGuard {
    private data class WindowRegistration(
        val token: Any,
        val setVisible: (Boolean) -> Unit,
        val setInputActive: (Boolean) -> Unit
    )

    @Volatile
    private var window: WindowRegistration? = null
    @Volatile
    private var inputActive = false
    private var captureActive = false
    private val captureMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isLogVisible(): Boolean = window != null

    internal fun isInputActive(): Boolean = inputActive

    suspend fun setInputActive(active: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            val changed = inputActive != active
            inputActive = active
            window?.setInputActive(active)
            if (active && changed && window != null) {
                // Layout updates must reach InputDispatcher before the first injected DOWN.
                awaitWindowUpdate("Timed out enabling log overlay touch-through before input")
            }
        }
    }

    // Window registration and visibility changes are confined to the main thread.
    internal fun register(
        token: Any,
        setVisible: (Boolean) -> Unit,
        setInputActive: (Boolean) -> Unit
    ) {
        window = WindowRegistration(token, setVisible, setInputActive)
        setVisible(!captureActive)
        setInputActive(inputActive)
    }

    internal fun unregister(token: Any) {
        if (window?.token === token) window = null
    }

    suspend fun <T> withoutLogs(block: suspend () -> T): T = captureMutex.withLock {
        try {
            val hidden = withContext(Dispatchers.Main.immediate) {
                captureActive = true
                window?.also { it.setVisible(false) } != null
            }
            if (hidden) {
                withContext(Dispatchers.Main.immediate) {
                    // Two frame boundaries allow the visibility traversal to reach the compositor.
                    awaitWindowUpdate("Timed out hiding the log overlay before capture")
                }
            }
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                captureActive = false
                // Restore the current registration only; a closed window must stay closed.
                window?.setVisible(true)
            }
        }
    }

    private suspend fun awaitWindowUpdate(failureMessage: String) {
        val framesReady = withTimeoutOrNull(250) {
            awaitFrame()
            awaitFrame()
            true
        }
        // Fail closed while compositor visibility or input window flags are uncertain.
        if (framesReady != true) throw IOException(failureMessage)
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                choreographer.removeFrameCallback(callback)
            } else {
                mainHandler.post { choreographer.removeFrameCallback(callback) }
            }
        }
    }
}
