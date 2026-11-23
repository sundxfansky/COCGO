package com.coc.suncode.core.ui.floatingwindows

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.hotupdate.HotUpdateManager
import com.coc.suncode.core.ui.web.SettingsRequestException
import com.coc.suncode.core.ui.web.SettingsWebPanel
import com.coc.suncode.core.ui.web.WebSettingsSession
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.loadjar.Loadjar
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.roundToInt

class UIWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    companion object {
        private const val NOTIFICATION_ID = 1000
        private const val HEIGHT_RATIO_MAIN = 0.9f
        private const val MAIN_WINDOW_SIDE_MARGIN_DP = 10f
        private const val DEFAULT_LOAD_STATUS = "加载中..."
        private const val LOADED = "Plugin loaded successfully"
        const val PROJECTION_ONLY = "projection_only"

        // Other in-app overlays yield while the main window owns the foreground.
        internal var isWindowVisible by mutableStateOf(false)
            private set
        internal var isActivityVisible by mutableStateOf(false)
        internal val isSettingsVisible get() = isWindowVisible || isActivityVisible

        // Signal that startForeground() has been called, used to synchronize
        // with ProjectionPermissionHelper before calling getMediaProjection()
        var foregroundReady = CompletableDeferred<Unit>()
            private set
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this).apply {
        performRestore(null)
    }
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    private val customViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore = customViewModelStore

    private val _onBackPressedDispatcher = OnBackPressedDispatcher {
        // Fallback action for back press
    }
    override val onBackPressedDispatcher: OnBackPressedDispatcher = _onBackPressedDispatcher

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val loadjar by lazy { Loadjar(this) }
    private val loadStatus = mutableStateOf(DEFAULT_LOAD_STATUS)
    private var isLoadStarted = false
    private var isLoadFinished = false
    private var hotUpdateStarted = false

    override fun onCreate() {
        super.onCreate()
        updateForegroundRecord()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceScope.launch {
            QqAuthorizationManager.status.collect {
                updateSettingsVisibility()
                if (!it.busy && it.hideSettings) startService(Intent(this@UIWindowService, ControlWindowService::class.java))
            }
        }
        serviceScope.launch {
            snapshotFlow { isActivityVisible }.collect { updateSettingsVisibility() }
        }
    }

    private fun startJarLoading() {
        if (!isLoadStarted) {
            isLoadStarted = true
            if (!hotUpdateStarted) {
                hotUpdateStarted = true
                startHotUpdateListener()
            }
            serviceScope.launch {
                // Prepare assets, verification and configuration before attaching the Compose window.
                var preparedStatus = DEFAULT_LOAD_STATUS
                try {
                    if (GlobalVars.pluginUI != null && GlobalVars.isConfigLoaded) {
                        preparedStatus = LOADED
                    } else withContext(Dispatchers.IO) {
                        loadjar.startLoading { preparedStatus = it }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    preparedStatus = "设备设置加载失败，请重试"
                    ShowMessage.record(preparedStatus, throwable = error)
                }
                loadStatus.value = preparedStatus
                isLoadFinished = true
                showFloatingWindow()
            }
        }
    }

    // Launch the hot update signal listener and watchdog on background threads
    private fun startHotUpdateListener() {
        serviceScope.launch(Dispatchers.IO) {
            HotUpdateManager.listenForSignal(this@UIWindowService)
        }
        serviceScope.launch(Dispatchers.IO) {
            HotUpdateManager.startWatchdog(this@UIWindowService)
        }
    }

    private fun showFloatingWindow() {
        if (!isLoadFinished || composeView != null || AppStateManager.currentMode == AppMode.Run) return

        val (width, height) = calculateWindowSize()
        val windowType = getWindowType()

        windowParams = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            title = "SunCodeSettingsOverlay"
            windowAnimations = 0
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@UIWindowService)
            setViewTreeSavedStateRegistryOwner(this@UIWindowService)
            setViewTreeViewModelStoreOwner(this@UIWindowService)
            setViewTreeOnBackPressedDispatcherOwner(this@UIWindowService)

            setContent {
                val status = loadStatus.value
                val active = GlobalVars.pluginUI
                val session = remember(active, status) {
                    if (status == LOADED && active != null) {
                        WebSettingsSession(this@UIWindowService, loadjar.createWebSettingsController(), ::handleUIIClose)
                    } else null
                }
                LaunchedEffect(session, GlobalVars.isAutoRunEnabled, isActivityVisible) {
                    if (!isActivityVisible) session?.runCountdown()
                }
                LaunchedEffect(AppStateManager.currentMode) {
                    if (AppStateManager.currentMode == AppMode.Run) closeMainUI()
                }
                BackHandler(enabled = session != null) { session?.close(play = false, refresh = false) }
                SettingsWebPanel(
                    modifier = Modifier.fillMaxSize(),
                    surface = "overlay",
                    onInteraction = { GlobalVars.isAutoRunEnabled = false }
                ) { method, params ->
                    when (method) {
                        "getStartupState" -> JsonObject().apply {
                            addProperty("ready", session != null)
                            addProperty("status", if (isLoadFinished) "LOCAL_ERROR" else "CHECKING")
                            addProperty("message", status)
                        }
                        "retryStartup" -> {
                            if (session == null && isLoadFinished) {
                                isLoadStarted = false
                                isLoadFinished = false
                                loadStatus.value = DEFAULT_LOAD_STATUS
                                startJarLoading()
                            }
                            JsonObject()
                        }
                        else -> session?.request(method, params)
                            ?: throw SettingsRequestException(503, "设备设置正在初始化")
                    }
                }
            }
        }

        windowManager.addView(composeView, windowParams)
        updateSettingsVisibility()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun updateSettingsVisibility() {
        val view = composeView ?: return
        val qqStatus = QqAuthorizationManager.status.value
        // Retain the draft while the launcher settings or external authorization owns the foreground.
        val hidden = isActivityVisible || qqStatus.busy || qqStatus.hideSettings
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        isWindowVisible = !hidden
        val hiddenFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowParams.flags = if (hidden) windowParams.flags or hiddenFlags else windowParams.flags and hiddenFlags.inv()
        windowManager.updateViewLayout(view, windowParams)
    }

    private fun handleUIIClose() {
        closeMainUI()
        if (!GlobalVars.isPlaying.value) {
            ShowMessage("当前已暂停运行。\n若要启动辅助，请通过悬浮窗启动。", false)
        }
        startService(Intent(this, ControlWindowService::class.java))
    }

    private fun calculateWindowSize(): Pair<Int, Int> {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // Size the native window to leave a real gap for input outside the settings panel.
        val sideMargin = (MAIN_WINDOW_SIDE_MARGIN_DP * displayMetrics.density).roundToInt()
        return Pair((screenWidth - 2 * sideMargin).coerceAtLeast(1), (screenHeight * HEIGHT_RATIO_MAIN).toInt())
    }

    private fun updateWindowSizeForCurrentMode() {
        if (composeView != null && ::windowParams.isInitialized) {
            val (width, height) = calculateWindowSize()
            windowParams.width = width
            windowParams.height = height
            windowManager.updateViewLayout(composeView, windowParams)
        }
    }

    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun closeMainUI() {
        if (composeView != null) {
            windowManager.removeView(composeView)
            composeView = null
        }
        isWindowVisible = false
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateWindowSizeForCurrentMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        isWindowVisible = false
        // Reset the signal so it can be re-awaited on next service start
        foregroundReady = CompletableDeferred()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        customViewModelStore.clear()
        if (composeView != null) {
            windowManager.removeViewImmediate(composeView)
            composeView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundRecord()
        // A projection grant requires a foreground service but does not open another settings window.
        if (intent == null || intent.getBooleanExtra(PROJECTION_ONLY, false)) return START_NOT_STICKY
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        QqAuthorizationManager.revealSettings()
        startJarLoading()
        showFloatingWindow()
        return START_NOT_STICKY
    }

    private fun updateForegroundRecord() {
        val notification = NotificationHelper.createNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Only use MEDIA_PROJECTION type — SPECIAL_USE is not declared in
                // the manifest for this service, and would cause startForeground()
                // to throw on API 34+ (Android 14+).
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            // Signal that the foreground service is now running
            foregroundReady.complete(Unit)
        } catch (e: Exception) {
            Timber.e(e, "UIWindowService: startForeground() failed, API=${Build.VERSION.SDK_INT}")
        }
    }
}
