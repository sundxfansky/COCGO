package com.coc.suncode.core.system.screencapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.graphics.createBitmap
import com.coc.suncode.core.system.accessibility.MyAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.coc.suncode.core.util.fileactions.LogHelper
import com.coc.suncode.core.ui.floatingwindows.LogOverlayCaptureGuard


object ScreenCaptureManager {
    private const val FIRST_FRAME_TIMEOUT_MILLIS = 1_000L
    private const val NEXT_FRAME_TIMEOUT_MILLIS = 100L
    private const val RETRY_FRAME_TIMEOUT_MILLIS = 1_000L
    private const val CACHE_MAX_AGE_MILLIS = 3_000L

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Cache for static screen strategy
    private var cachedBitmap: Bitmap? = null
    private var cachedCaptureResult: CaptureResult? = null
    private var lastFrameTimestamp: Long = 0

    /** Serializes ImageReader callbacks with teardown to avoid closing an in-flight Image. */
    private val resourceLock = Any()
    private var resourceGeneration = 0L

    // Skip MediaProjection and go straight to shell screencap after first failure
    private var useShellFallback = false

    // --- New: Dedicated background thread for handling screenshots ---
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var appContext: Context? = null

    private var cachedResultCode: Int? = null
    private var cachedIntentData: Intent? = null

    private val captureMutex = Mutex()

    private fun registerProjection(projection: MediaProjection): MediaProjection {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(resourceLock) {
                    // A delayed callback from an old permission session must not tear down
                    // resources already created for its replacement.
                    if (mediaProjection !== projection) return
                    cleanupDisplayResources()
                    mediaProjection = null
                }
                LogHelper.showDebugInfo("capture: MediaProjection stopped; resources invalidated")
            }
        }, backgroundHandler)
        return projection
    }

    /**
     * Initialize the background thread. ImageReader can only work when HandlerThread is ready.
     */
    private fun ensureHandlerThread() = synchronized(resourceLock) {
        if (handlerThread == null || !handlerThread!!.isAlive) {
            handlerThread = HandlerThread("ScreenCapBackground").apply { start() }
            backgroundHandler = Handler(handlerThread!!.looper)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        updateMetrics()
        ensureHandlerThread() // Start thread during initialization
    }

    fun getContext(): Context? = appContext

    private fun updateMetrics() {
        synchronized(resourceLock) {
            val context = appContext ?: return@synchronized
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                screenWidth = metrics.bounds.width()
                screenHeight = metrics.bounds.height()
                screenDensity = context.resources.configuration.densityDpi
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi
            }
        }
    }

    private fun ensureProjection(): MediaProjection? = synchronized(resourceLock) {
        if (mediaProjection == null) {
            val code = cachedResultCode
            val data = cachedIntentData
            if (code != null && data != null) {
                ensureHandlerThread()
                // getMediaProjection() requires a running foreground service with
                // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION on Android 14+.
                LogHelper.showDebugInfo("ensureProjection: re-creating MediaProjection (API=${Build.VERSION.SDK_INT})")
                try {
                    mediaProjection = mediaProjectionManager?.getMediaProjection(code, data)
                        ?.let(::registerProjection)
                } catch (e: SecurityException) {
                    LogHelper.showDebugInfo("ensureProjection: getMediaProjection() failed — " +
                            "foreground service with MEDIA_PROJECTION type may not be running: ${e.message}")
                }
            }
        }
        mediaProjection
    }

    /**
     * Request permission or attempt to take screenshot directly.
     */
    suspend fun requestPermission(launcher: ActivityResultLauncher<Intent>) {
        if (cachedResultCode != null && cachedIntentData != null) {
            // If permission is already cached, try to take a screenshot directly
            if (withContext(Dispatchers.IO) { takeScreenshot() }) return
            reset()
        }

        // Keep the accessibility logic here
        // Root shell calls and the accessibility rebind delay must not block startup rendering.
        withContext(Dispatchers.IO) { AutoGrantTool.forceEnableAccessibility() }
        MyAccessibilityService.isDetectionEnabled = true

        mediaProjectionManager?.let {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Force whole-screen capture on Android 14+, skipping the app-picker dialog
                it.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                it.createScreenCaptureIntent()
            }
            launcher.launch(intent)
        }
    }

    /**
     * Complete reset - Clear all content including cached credentials.
     * Use this when you want to force a new permission request dialog.
     */
    private fun reset() = synchronized(resourceLock) {
        try {
            // Stop the current projection session
            mediaProjection?.stop()
        } catch (e: Exception) {
            LogHelper.showDebugInfo("reset: Error stopping mediaProjection: ${e.message}")
        }

        // Clean up resources like VirtualDisplay and ImageReader
        cleanupDisplayResources()
        mediaProjection = null

        // Clear cached permission credentials
        cachedResultCode = null
        cachedIntentData = null
    }

    private fun ensureVirtualDisplay(projection: MediaProjection) = synchronized(resourceLock) {
        if (virtualDisplay == null) {
            // If ImageReader is needed, ensure it's ready
            if (imageReader == null) prepareImageReader()

            try {
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    backgroundHandler
                )
            } catch (e: Exception) {
                LogHelper.showDebugInfo("ensureVirtualDisplay: Error creating VirtualDisplay: ${e.message}")
                cleanupDisplayResources()
            }
        } else {
            // If VirtualDisplay already exists, check if size has changed
            // Note: resize is not supported in all versions, simple handling here: if size changed, destroy and recreate
            // But usually updateMetrics has been called in capture(), so we can assume metrics are new
            // Simple check if imageReader matches
            if (imageReader?.width != screenWidth || imageReader?.height != screenHeight) {
                LogHelper.showDebugInfo("ensureVirtualDisplay: Size changed, recreating resources")
                cleanupDisplayResources()
                prepareImageReader()
                try {
                    virtualDisplay = projection.createVirtualDisplay(
                        "ScreenCapture",
                        screenWidth,
                        screenHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader?.surface,
                        null,
                        backgroundHandler
                    )
                } catch (e: Exception) {
                    LogHelper.showDebugInfo("ensureVirtualDisplay: Error recreating VirtualDisplay: ${e.message}")
                    // Clean up dangling imageReader to prevent Surface/native buffer leak
                    cleanupDisplayResources()
                }
            }
        }
    }

    /**
     * Synchronous style screenshot method (non-coroutine version).
     * Update: Use backgroundHandler instead of mainHandler.
     */
    fun takeScreenshot(): Boolean {
        updateMetrics()
        val projection = ensureProjection() ?: run {
            LogHelper.showDebugInfo("takeScreenshot: Failed to ensure projection")
            return false
        }
        ensureHandlerThread() // 确保后台线程已启动

        return try {
            ensureVirtualDisplay(projection)
            virtualDisplay != null
        } catch (e: Exception) {
            LogHelper.showDebugInfo("takeScreenshot: Error during capture setup: ${e.message}")
            cleanupDisplayResources()
            false
        }
    }

    private fun prepareImageReader() = synchronized(resourceLock) {
        if (imageReader == null || imageReader?.width != screenWidth || imageReader?.height != screenHeight) {
            imageReader?.setOnImageAvailableListener(null, null)
            runCatching { imageReader?.close() }
            // Use RGBA_8888 format, maxImages set to 2 is sufficient
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        }
    }

    fun onPermissionGranted(resultCode: Int, data: Intent) {
        if (resultCode == Activity.RESULT_OK) {
            cachedResultCode = resultCode
            cachedIntentData = data
            // Re-enable MediaProjection path since new permission was granted
            useShellFallback = false
            ensureHandlerThread()
            synchronized(resourceLock) {
                val previous = mediaProjection
                mediaProjection = null
                runCatching { previous?.stop() }
                cleanupDisplayResources()
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                    ?.let(::registerProjection)
            }
            LogHelper.showDebugInfo("capture: screen permission granted (API=${Build.VERSION.SDK_INT})")
        } else {
            LogHelper.showDebugInfo("onPermissionGranted: Permission denied (resultCode=$resultCode)")
        }
    }

    private fun cleanupDisplayResources() = synchronized(resourceLock) {
        // Invalidate callbacks before releasing native buffers. The callback and this method
        // share resourceLock, so an already-running callback finishes before close() executes.
        resourceGeneration++
        val reader = imageReader
        val display = virtualDisplay
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        imageReader = null

        // Cache references are private; GC releases them once concurrent copies finish.
        cachedBitmap = null
        cachedCaptureResult = null
        lastFrameTimestamp = 0

        // Do NOT set mediaProjection to null here, so we can reuse it
    }

    fun releaseAll() = synchronized(resourceLock) {
        runCatching { mediaProjection?.stop() }
        cleanupDisplayResources()
        mediaProjection = null
        cachedResultCode = null
        cachedIntentData = null
        // Stop background thread
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
        // Release persistent shell connection
        ShellScreenCapture.release()
    }

    data class CaptureResult(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val pixelStride: Int,
        val rowStride: Int
    )

    /**
     * Improved coroutine screenshot method.
     * Tries MediaProjection first; falls back to shell screencap -p on failure.
     */
    suspend fun capture(asBitmap: Boolean = true): Any? = captureMutex.withLock {
        LogOverlayCaptureGuard.withoutLogs { captureWithoutLogs(asBitmap) }
    }

    private suspend fun captureWithoutLogs(asBitmap: Boolean): Any? {
        if (LogOverlayCaptureGuard.isLogVisible()) {
            // Projection caches can retain a frame containing logs after the window is hidden.
            return captureViaShell(asBitmap)
        }
        // If MediaProjection already failed, skip directly to shell
        if (useShellFallback) {
            return captureViaShell(asBitmap)
        }

        // Try MediaProjection first
        val projectionResult = try {
            ensureHandlerThread()

            // Internal function: Attempt a complete screenshot process (reuse or create new)
            suspend fun attemptCapture(): Any? {
                return suspendCancellableCoroutine { cont ->
                    updateMetrics()
                    val projection = ensureProjection()

                    if (projection == null) {
                        LogHelper.showDebugInfo("capture: Projection is null")
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    ensureVirtualDisplay(projection)
                    val readerAndGeneration = synchronized(resourceLock) {
                        imageReader?.let { it to resourceGeneration }
                    }
                    if (readerAndGeneration == null) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val reader = readerAndGeneration.first
                    val generation = readerAndGeneration.second

                    fun isCurrentReader(): Boolean = synchronized(resourceLock) {
                        generation == resourceGeneration && reader === imageReader
                    }

                    if (!isCurrentReader()) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    // Helper to process image
                    fun processImage(image: Image) {
                        // The ImageReader may be torn down by MediaProjection.onStop. Keep image
                        // extraction and teardown on the same monitor so a closed Image is never
                        // accessed by a stale callback from the previous generation.
                        synchronized(resourceLock) {
                            var pendingBitmap: Bitmap? = null
                            try {
                                if (generation != resourceGeneration || reader !== imageReader) {
                                    return@synchronized
                                }
                                val plane = image.planes[0]
                                val buffer: ByteBuffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                // Use this reader's immutable dimensions even if display metrics
                                // change on the activity thread while this frame is being copied.
                                val frameWidth = reader.width
                                val frameHeight = reader.height
                                val rowPadding = rowStride - pixelStride * frameWidth

                                if (asBitmap) {
                                    val bitmap = createBitmap(frameWidth + rowPadding / pixelStride, frameHeight)
                                    pendingBitmap = bitmap
                                    bitmap.copyPixelsFromBuffer(buffer)
                                    val finalBitmap = if (rowPadding == 0) {
                                        bitmap
                                    } else {
                                        Bitmap.createBitmap(bitmap, 0, 0, frameWidth, frameHeight).also {
                                            if (it != bitmap) bitmap.recycle()
                                            pendingBitmap = it
                                        }
                                    }
                                    // Keep a private cache; callers own independent bitmap copies.
                                    cachedBitmap = finalBitmap
                                    pendingBitmap = null
                                    lastFrameTimestamp = SystemClock.elapsedRealtime()

                                    if (cont.isActive) {
                                        val ownedCopy = finalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        cont.resume(ownedCopy) { _, abandoned, _ -> abandoned?.recycle() }
                                    }
                                } else {
                                    val capacity = buffer.capacity()
                                    val directCopy = ByteBuffer.allocateDirect(capacity)
                                    buffer.rewind()
                                    directCopy.put(buffer)
                                    directCopy.flip()

                                    val result = CaptureResult(
                                        buffer = directCopy,
                                        width = frameWidth,
                                        height = frameHeight,
                                        pixelStride = pixelStride,
                                        rowStride = rowStride
                                    )

                                    cachedCaptureResult = result
                                    lastFrameTimestamp = SystemClock.elapsedRealtime()

                                    if (cont.isActive) cont.resume(result)
                                }
                            } catch (e: Exception) {
                                if (pendingBitmap != null && !pendingBitmap!!.isRecycled) {
                                    pendingBitmap!!.recycle()
                                }
                                // Cancellation and teardown can close an Image between the
                                // callback and extraction; treat it as a stale frame quietly.
                                if (generation == resourceGeneration && reader === imageReader) {
                                    LogHelper.showDebugInfo("capture: Error processing current image: ${e.message}")
                                    if (cont.isActive) cont.resume(null)
                                }
                            } finally {
                                runCatching { image.close() }
                            }
                        }
                    }

                    // 1. Try to get existing latest image immediately
                    synchronized(resourceLock) {
                        if (!isCurrentReader()) {
                            if (cont.isActive) cont.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        try {
                            val latestImage = reader.acquireLatestImage()
                            if (latestImage != null) {
                                processImage(latestImage)
                                return@suspendCancellableCoroutine
                            }
                        } catch (error: Exception) {
                            LogHelper.showDebugInfo("capture: ImageReader acquire failed: ${error.message}")
                        }

                        // 2. No image available, wait for new one
                        reader.setOnImageAvailableListener({ availableReader ->
                            val image = synchronized(resourceLock) {
                                if (!isCurrentReader()) null else try {
                                    availableReader.setOnImageAvailableListener(null, null)
                                    availableReader.acquireLatestImage()
                                } catch (error: Exception) {
                                    LogHelper.showDebugInfo("capture: ImageReader callback acquire failed: ${error.message}")
                                    null
                                }
                            }
                            if (image != null) {
                                processImage(image)
                            } else if (cont.isActive) {
                                cont.resume(null)
                            }
                        }, backgroundHandler)
                    }

                    cont.invokeOnCancellation {
                        synchronized(resourceLock) {
                            if (generation == resourceGeneration && reader === imageReader) {
                                reader.setOnImageAvailableListener(null, null)
                            }
                        }
                    }
                }
            }

            // Allow the compositor to produce the first buffer before considering teardown.
            val frameTimeout = synchronized(resourceLock) {
                if (lastFrameTimestamp == 0L) FIRST_FRAME_TIMEOUT_MILLIS else NEXT_FRAME_TIMEOUT_MILLIS
            }
            val result = withTimeoutOrNull(frameTimeout) {
                attemptCapture()
            }

            if (result != null) {
                result
            } else {
                // 2. Timeout: Check if we can reuse cached frame
                val now = SystemClock.elapsedRealtime()
                val cacheAge = if (lastFrameTimestamp > 0) now - lastFrameTimestamp else Long.MAX_VALUE
                val isCacheValid = cacheAge in 0 until CACHE_MAX_AGE_MILLIS

                if (isCacheValid) {
                    if (asBitmap && cachedBitmap != null) {
                        cachedBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    } else if (!asBitmap && cachedCaptureResult != null) {
                        cachedCaptureResult
                    } else {
                        null
                    }
                } else {
                    // 3. Cache expired or not available -> Force reset
                    LogHelper.showDebugInfo("capture: No fresh frame (age=${if (cacheAge == Long.MAX_VALUE) "none" else "${cacheAge}ms"}), recreating resources")
                    cleanupDisplayResources()

                    // 4. Retry after reset (longer timeout to allow setup)
                    withTimeoutOrNull(RETRY_FRAME_TIMEOUT_MILLIS) {
                        attemptCapture()
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopping the script must not permanently switch capture to the shell fallback.
            throw e
        } catch (e: Exception) {
            LogHelper.showDebugInfo("capture: MediaProjection capture failed: ${e.message}")
            null
        }

        if (projectionResult != null) {
            return projectionResult
        }

        // Fallback to shell screencap when MediaProjection fails or returns null
        useShellFallback = true
        LogHelper.showDebugInfo("capture: Falling back to shell screencap -p (will skip MediaProjection next time)")
        return captureViaShell(asBitmap)
    }

    /**
     * Fallback capture method delegating to ShellScreenCapture which maintains a
     * persistent root shell and reads screencap output directly from stdout.
     */
    private suspend fun captureViaShell(asBitmap: Boolean): Any? {
        return ShellScreenCapture.capture(asBitmap)
    }
}

