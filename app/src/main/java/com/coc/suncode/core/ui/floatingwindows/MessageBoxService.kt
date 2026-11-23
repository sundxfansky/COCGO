package com.coc.suncode.core.ui.floatingwindows

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import com.coc.suncode.core.ui.web.SettingsRequestException
import com.coc.suncode.core.ui.web.SettingsWebPanel
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.exit.AppExitHelper
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// Lightweight message payload for in-process delivery via SharedFlow
data class MessageData(
    val text: String, val x: Int, val y: Int, val fontSize: Float, val duration: Long
)

// Ad item from the server API
data class AdItem(
    val content: String, val link: String?, val topAd: Int
)

// Payload carrying ad items and the display duration for the overlay
data class AdOverlayData(
    val items: List<AdItem>, val durationSeconds: Int
)

class MessageBoxService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val INACTIVITY_TIMEOUT_MS = 2500L

        // Flag to let MessageBoxHelper bypass Binder IPC when the service is alive
        val isRunning = AtomicBoolean(false)

        // In-process message channel; extraBufferCapacity ensures tryEmit() never fails
        val messageFlow = MutableSharedFlow<MessageData>(
            extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        // Ad overlay channel; null signals dismiss
        val adFlow = MutableSharedFlow<AdOverlayData?>(
            extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    // Defer performRestore() to onCreate() so class-construction failures
    // cannot prevent startForeground() from being called.
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var inactivityJob: Job? = null

    private var messageX by mutableIntStateOf(1280)
    private var messageY by mutableIntStateOf(720)
    private var messageText by mutableStateOf("")
    private var messageFontSize by mutableStateOf(8.sp)
    private var messageDuration by mutableLongStateOf(2000L)
    private var isVisible by mutableStateOf(false)

    // To handle multiple concurrent requests or updates, we might need a trigger
    private var showTrigger by mutableLongStateOf(0L)

    // --- Ad overlay state (second window, independent of debug messages) ---
    private var adComposeView: ComposeView? = null
    private var adItems by mutableStateOf<List<AdItem>>(emptyList())
    private var isAdVisible by mutableStateOf(false)
    private var adDurationSeconds by mutableIntStateOf(15)
    private var adCountdown by mutableIntStateOf(15)

    override fun onCreate() {
        super.onCreate()
        // Must call startForeground() before anything else to satisfy the
        // system contract from startForegroundService().
        updateForegroundRecord()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        isRunning.set(true)

        // Collect in-process messages delivered via SharedFlow (bypasses Binder IPC)
        serviceScope.launch {
            messageFlow.collect { msg ->
                applyMessage(msg)
            }
        }

        // Collect ad overlay show/dismiss signals
        serviceScope.launch {
            adFlow.collect { data ->
                if (data != null) {
                    adItems = data.items.sortedByDescending { it.topAd }
                    adDurationSeconds = data.durationSeconds
                    adCountdown = data.durationSeconds
                    isAdVisible = true
                    showAdWindow()
                } else {
                    isAdVisible = false
                    removeAdWindow()
                }
            }
        }

        try {
            showWindow()
        } catch (e: Exception) {
            Timber.e(e, "MessageBoxService: showWindow() failed")
        }
    }

    // Apply message data to Compose state and reset the inactivity timer
    private fun applyMessage(msg: MessageData) {
        messageText = msg.text
        messageX = msg.x
        messageY = msg.y
        messageFontSize = msg.fontSize.sp
        messageDuration = msg.duration
        // Also check at delivery time: a cold-start Intent may predate opening the log window.
        isVisible = msg.text.isNotEmpty() && !ShowMessage.isLogWindowVisible()
        showTrigger++
        resetInactivityTimer()
    }

    // Cancel and restart the inactivity timer; service stops after INACTIVITY_TIMEOUT_MS of silence.
    // While the ad overlay is visible, skip self-stop to keep the service alive.
    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            if (!isAdVisible) {
                stopSelf()
            }
        }
    }

    private fun showWindow() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0 // Disable animations
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MessageBoxService)
            setViewTreeSavedStateRegistryOwner(this@MessageBoxService)

            setContent {
                MessageBoxContent()
            }
        }

        windowManager.addView(composeView, params)
    }

    @Composable
    private fun MessageBoxContent() {
        var boxSize by remember { mutableStateOf(IntSize.Zero) }

        LaunchedEffect(showTrigger) {
            if (isVisible) {
                delay(messageDuration)
                isVisible = false
                // Service stays alive; inactivity timer handles shutdown
            }
        }

        if (isVisible && !UIWindowService.isSettingsVisible) {
            // Effect to update window position based on size and target rb-corner
            LaunchedEffect(messageX, messageY, boxSize) {
                if (composeView != null && boxSize != IntSize.Zero) {
                    val params = composeView!!.layoutParams as WindowManager.LayoutParams

                    // messageX, messageY is the Right-Bottom corner.
                    // Top-Left = Right-Bottom - Size
                    val targetX = messageX - boxSize.width
                    val targetY = messageY - boxSize.height

                    params.x = targetX
                    params.y = targetY

                    try {
                        windowManager.updateViewLayout(composeView, params)
                    } catch (_: Exception) {
                    }
                }
            }

            HtmlMessage(
                text = messageText,
                fontSize = messageFontSize,
                modifier = Modifier.onGloballyPositioned { boxSize = it.size }
            )
        }
    }

    @Composable
    private fun HtmlMessage(text: String, fontSize: TextUnit, modifier: Modifier) {
        val density = LocalDensity.current
        val fontPixels = with(density) { fontSize.toPx() }
        val layout = remember(text, fontPixels, resources.displayMetrics.widthPixels) {
            val paint = TextPaint().apply { textSize = fontPixels }
            val measuredWidth = text.lineSequence().maxOf { paint.measureText(it) }
            val width = measuredWidth.roundToInt().plus(8)
                .coerceIn(8, resources.displayMetrics.widthPixels.coerceAtLeast(8))
            val height = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 4)
                .build().height + 4
            IntSize(width, height)
        }
        val (width, height) = with(density) { layout.width.toDp() to layout.height.toDp() }
        AndroidView(
            modifier = modifier.size(width, height),
            factory = { context ->
                WebView(context).apply {
                    // Transient text is escaped and rendered without a bridge or network access.
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(0)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                }
            },
            update = { view ->
                val html = """
                    <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                    <body style="margin:0;padding:0 2px;background:black;color:white;font-family:sans-serif;
                        font-size:${fontSize.value}px;white-space:pre-wrap;overflow-wrap:anywhere">${TextUtils.htmlEncode(text)}</body>
                """.trimIndent()
                if (view.tag != html) {
                    view.tag = html
                    view.loadData(Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP), "text/html; charset=utf-8", "base64")
                }
            },
            onRelease = { it.destroy() }
        )
    }

    // --- Ad overlay window management ---

    private fun showAdWindow() {
        if (adComposeView != null) return

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val adParams = WindowManager.LayoutParams(
            (displayMetrics.widthPixels * 0.9).roundToInt(),
            (displayMetrics.heightPixels * 0.7).roundToInt(),
            windowType,
            // Focusable so links are clickable (no FLAG_NOT_FOCUSABLE)
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            windowAnimations = 0
        }

        adComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MessageBoxService)
            setViewTreeSavedStateRegistryOwner(this@MessageBoxService)

            setContent {
                AdOverlayContent()
            }
        }

        try {
            windowManager.addView(adComposeView, adParams)
        } catch (e: Exception) {
            Timber.e(e, "MessageBoxService: showAdWindow() failed")
            adComposeView = null
        }
    }

    private fun removeAdWindow() {
        if (adComposeView != null) {
            try {
                windowManager.removeViewImmediate(adComposeView)
            } catch (_: IllegalArgumentException) {
                // View not attached
            }
            adComposeView = null
        }
    }

    @Composable
    private fun AdOverlayContent() {
        LaunchedEffect(adDurationSeconds) {
            for (remaining in adDurationSeconds downTo 1) {
                adCountdown = remaining
                delay(1000L)
            }
            adCountdown = 0
        }
        SettingsWebPanel(surface = "advertisement", modifier = Modifier.fillMaxSize()) { method, params ->
            when (method) {
                "getAdvertisement" -> JsonObject().apply {
                    addProperty("seconds", adCountdown)
                    add("items", JsonArray().apply {
                        adItems.forEach { item ->
                            add(JsonObject().apply {
                                addProperty("content", item.content)
                                addProperty("hasLink", item.link != null)
                            })
                        }
                    })
                }
                "openAdvertisementLink" -> {
                    // Accept only links from the currently displayed items and standard browser schemes.
                    val index = params.get("index")?.asInt ?: -1
                    val uri = adItems.getOrNull(index)?.link?.toUri()
                    require(uri?.scheme in setOf("http", "https")) { "无效的链接" }
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    JsonObject()
                }
                "exitAdvertisement" -> {
                    AppExitHelper.exitApplication(this@MessageBoxService)
                    JsonObject()
                }
                else -> throw SettingsRequestException(404, "不支持的操作")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundRecord()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Handle cold-start messages delivered via Intent
        intent?.let {
            val text = it.getStringExtra("text") ?: ""
            if (text.isNotEmpty()) {
                applyMessage(
                    MessageData(
                        text = text, x = it.getIntExtra("x", 1280), y = it.getIntExtra("y", 720), fontSize = it.getFloatExtra("fontSize", 15f), duration = it.getLongExtra("duration", 2000L)
                    )
                )
            }
        }

        return START_NOT_STICKY
    }

    private var isForeground = false

    private fun updateForegroundRecord() {
        if (isForeground) return

        try {
            val notification = NotificationHelper.createNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: must specify a foreground service type matching the manifest
                startForeground(
                    1000, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(1000, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            // If startForeground() fails the service is doomed — stop gracefully
            // instead of letting the system throw RemoteServiceException later.
            Timber.e(e, "MessageBoxService: startForeground() failed, API=${Build.VERSION.SDK_INT}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (composeView != null) {
            try {
                windowManager.removeViewImmediate(composeView)
            } catch (_: IllegalArgumentException) {
                // View not attached
            }
            composeView = null
        }
        removeAdWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
