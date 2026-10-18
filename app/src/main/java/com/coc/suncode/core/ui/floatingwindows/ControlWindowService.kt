package com.coc.suncode.core.ui.floatingwindows

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.hotupdate.HotUpdateManager
import com.coc.suncode.core.util.basic.RunShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.exit.AppExitHelper.restoreDefaultInputMethod
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.topjohnwu.superuser.Shell
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ControlWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var controlComposeView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isSwitchingAccount by mutableStateOf(false)

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this).apply {
        performRestore(null)
    }
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        updateForegroundRecord()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showControlWindow()
        startBotLogic()
        startHotUpdateListener()
        startOomProtection()
    }

    private fun startBotLogic() {
        serviceScope.launch {
            snapshotFlow { AppStateManager.currentMode }
                .collect { mode ->
                    if (mode == AppMode.Run) {
                        BotRuntime.start(serviceScope) { GlobalVars.pluginUI?.runBot() }
                    } else {
                        BotRuntime.stop()
                    }
                }
        }
    }


    // Launch the hot update signal listener and watchdog on background threads
    private fun startHotUpdateListener() {
        serviceScope.launch(Dispatchers.IO) {
            HotUpdateManager.listenForSignal(this@ControlWindowService)
        }
        serviceScope.launch(Dispatchers.IO) {
            HotUpdateManager.startWatchdog(this@ControlWindowService)
        }
    }

    // Periodically enforce oom_score_adj = -1000 to prevent OOM killer from targeting this process
    private fun startOomProtection() {
        serviceScope.launch(Dispatchers.IO) {
            val pid = Process.myPid()
            while (true) {
                try {
                    val current = RunShell.runAndGetFirst(
                        "cat /proc/$pid/oom_score_adj",
                        isCheckIsPlaying = false
                    )
                    if (current.trim() != "-1000") {
                        RunShell.runNoOutput(
                            "echo -1000 > /proc/$pid/oom_score_adj",
                            isCheckIsPlaying = false
                        )
                    }
                } catch (_: Exception) { }
                delay(5000)
            }
        }
    }

    private fun showControlWindow() {
        val params = createControlWindowLayoutParams()

        controlComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ControlWindowService)
            setViewTreeSavedStateRegistryOwner(this@ControlWindowService)

            setContent {
                ControlWindowContainer { isAtRightSide, horizontalOffset, currentY, contentWidth ->
                    val edgeGravity = Gravity.TOP or if (isAtRightSide) Gravity.RIGHT else Gravity.LEFT
                    // Width changes need no follow-up x correction, so the icon cannot bounce.
                    if (params.gravity != edgeGravity || params.x != horizontalOffset || params.y != currentY || params.width != contentWidth) {
                        params.gravity = edgeGravity
                        params.x = horizontalOffset
                        params.y = currentY
                        params.width = contentWidth
                        if (isAttachedToWindow) windowManager.updateViewLayout(this, params)
                    }
                }
            }
        }

        windowManager.addView(controlComposeView, params)
    }

    @Composable
    private fun ControlWindowContainer(
        onWindowPositionUpdate: (Boolean, Int, Int, Int) -> Unit
    ) {
        var interactionCount by remember { mutableIntStateOf(0) }
        var componentSize by remember { mutableStateOf(IntSize.Zero) }
        var contentWidth by remember { mutableIntStateOf(WindowManager.LayoutParams.WRAP_CONTENT) }
        var isAtRightSide by remember { mutableStateOf(true) }
        var horizontalOffset by remember { mutableStateOf(0f) }
        var currentY by remember { mutableStateOf(360f) }
        var isDragging by remember { mutableStateOf(false) }

        // The controls use WRAP_CONTENT; their window size is not the display size.
        val configuration = LocalConfiguration.current
        val screenSize = remember(configuration) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                IntSize(bounds.width(), bounds.height())
            } else {
                val metrics = resources.displayMetrics
                IntSize(metrics.widthPixels, metrics.heightPixels)
            }
        }
        val screenWidth = screenSize.width
        val screenHeight = screenSize.height
        val currentX = if (isAtRightSide) {
            screenWidth - componentSize.width - horizontalOffset.roundToInt()
        } else {
            horizontalOffset.roundToInt()
        }

        // Read the desired width during composition so measurement changes schedule a native resize.
        val windowWidth = contentWidth
        val windowOffset = horizontalOffset.roundToInt()
        val windowY = currentY.roundToInt()
        SideEffect {
            onWindowPositionUpdate(isAtRightSide, windowOffset, windowY, windowWidth)
        }

        LaunchedEffect(screenWidth, screenHeight) {
            while (true) {
                if (GlobalVars.updateWindowPosition && !isDragging) {
                    isAtRightSide = GlobalVars.absorbEdge != 1
                    horizontalOffset = 0f
                    currentY = (screenHeight * (GlobalVars.absorbYPercentage / 100f))
                        .coerceIn(0f, (screenHeight - componentSize.height).coerceAtLeast(0).toFloat())
                }
                delay(500)
            }
        }

        Box(modifier = Modifier.onSizeChanged { componentSize = it }) {
            ControlWindow(
                externalInteractionCount = interactionCount,
                isAtRightSide = isAtRightSide,
                currentX = currentX,
                currentY = currentY.roundToInt(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onOpenMainUI = {
                    val intent =
                        Intent(this@ControlWindowService, UIWindowService::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    GlobalVars.isAutoRunEnabled = true
                    GlobalVars.autoRunTimer = 60
                    GlobalVars.updateWindowPosition = false
                    startService(intent)
                },
                isSwitchingAccount = isSwitchingAccount,
                onSwitchAccount = ::switchToAccount,
                onContentWidthChange = { contentWidth = it.coerceAtMost(screenWidth) },
                isDragging = isDragging,
                onDragStart = {
                    interactionCount++
                    isDragging = true
                },
                onDragEnd = {
                    // Convert to screen coordinates before changing the native docking gravity.
                    val left = if (isAtRightSide) {
                        screenWidth - componentSize.width - horizontalOffset
                    } else {
                        horizontalOffset
                    }
                    isAtRightSide = left + componentSize.width / 2f >= screenWidth / 2f
                    horizontalOffset = 0f
                    isDragging = false
                },
                onDrag = { dx, dy ->
                    // Right gravity measures x from the right edge; retain subpixel drag movement.
                    horizontalOffset = (horizontalOffset + if (isAtRightSide) -dx else dx)
                        .coerceIn(0f, (screenWidth - componentSize.width).coerceAtLeast(0).toFloat())
                    currentY = (currentY + dy)
                        .coerceIn(0f, (screenHeight - componentSize.height).coerceAtLeast(0).toFloat())
                    interactionCount++
                }
            )
        }
    }

    internal fun switchToAccount(accountNumber: Int) {
        if (isSwitchingAccount || GlobalVars.isSwitchingAccount || GlobalVars.isAdPlaying ||
            AppStateManager.currentMode != AppMode.Run
        ) return
        val plugin = GlobalVars.pluginUI
        val switcher = plugin as? RuntimeAccountSwitcher ?: return
        // Claim the operation before launching so a second tap cannot queue another restore.
        isSwitchingAccount = true
        GlobalVars.isSwitchingAccount = true
        GlobalVars.isPlaying.value = false
        GlobalVars.isAutoRunEnabled = false
        serviceScope.launch {
            try {
                // Join the old script before replacing its game files or restarting the game.
                BotRuntime.stop()
                withContext(Dispatchers.IO) {
                    restoreDefaultInputMethod()
                    switcher.switchToAccount(accountNumber)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ShowMessage("切号失败，辅助保持暂停：${error.message}", isChecking = false)
            } finally {
                GlobalVars.isPlaying.value = false
                GlobalVars.isSwitchingAccount = false
                isSwitchingAccount = false
                if (isActive && AppStateManager.currentMode == AppMode.Run && GlobalVars.pluginUI === plugin) {
                    // Re-enter at the script's pause gate, ready for an explicit Play tap later.
                    BotRuntime.start(serviceScope) { plugin.runBot() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (controlComposeView != null) {
            windowManager.removeView(controlComposeView)
            controlComposeView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Shell.cmd("am start -n com.coc.suncode/.MainActivity >>/dev/null 2>&1").exec()
        GlobalVars.autoRunTimer = 5
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Detect START_STICKY restart: system delivers null intent after LMKD kill
        if (intent == null) {
            // Relaunch MainActivity to re-initialize the full app (JAR loading, etc.)
            Shell.cmd("am start -n com.coc.suncode/.MainActivity >>/dev/null 2>&1").exec()
            GlobalVars.autoRunTimer = 5
        }
        updateForegroundRecord()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        return START_STICKY
    }

    private fun updateForegroundRecord() {
        val notification = NotificationHelper.createNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Only use MEDIA_PROJECTION type — SPECIAL_USE is not declared in
                // the manifest for this service, and would cause startForeground()
                // to throw on API 34+ (Android 14+).
                startForeground(
                    1000, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(1000, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "ControlWindowService: startForeground() failed, API=${Build.VERSION.SDK_INT}")
        }
    }
}

/** Shared window geometry for the control service and platform-level animation checks. */
internal fun createControlWindowLayoutParams(): WindowManager.LayoutParams {
    val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    return WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Native edge gravity anchors width changes in the same layout pass as the animation.
        gravity = Gravity.TOP or Gravity.RIGHT
        x = 0
        y = 360
        title = "SunCodeControlOverlay"
        windowAnimations = 0
    }
}
