package com.coc.suncode.core.system.checkpermissions

import android.content.Intent
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coc.suncode.MainActivity
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.core.ui.floatingwindows.ControlWindowService
import com.coc.suncode.core.ui.floatingwindows.UIWindowService
import com.coc.suncode.core.ui.web.SettingsRequestException
import com.coc.suncode.core.ui.web.SettingsWebPanel
import com.coc.suncode.core.ui.web.WebSettingsSession
import com.coc.suncode.core.util.basic.ServerShell
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.loadjar.Loadjar
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CheckRootScreen() {
    val activity = LocalContext.current as MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val loader = remember(activity) { Loadjar(activity.applicationContext) }
    var status by remember { mutableStateOf(RootStatus.CHECKING) }
    var failure by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf<WebSettingsSession?>(null) }
    val activityVisible = UIWindowService.isActivityVisible

    // Bootstrap in the background while the final settings WebView displays startup status.
    LaunchedEffect(retry) {
        status = RootStatus.CHECKING
        failure = ""
        try {
            status = PermissionManager.checkAndGrantPermissions(activity) { status = it }
            if (status != RootStatus.GRANTED) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                if (GlobalVars.defaultInputMethod == null) {
                    val user = Process.myUid() / 100_000
                    val result = ServerShell.exec("settings --user $user get secure default_input_method")
                    if (result.isSuccess) GlobalVars.defaultInputMethod = result.out.firstOrNull()
                }
                if (GlobalVars.pluginUI == null || !GlobalVars.isConfigLoaded) loader.startLoading {}
            }
            check(GlobalVars.pluginUI != null && GlobalVars.isConfigLoaded) { "插件或配置加载失败，请重试" }
            AppStateManager.setMode(AppMode.Main)
            GlobalVars.isPlaying.value = false
            GlobalVars.isAutoRunEnabled = true
            GlobalVars.autoRunTimer = 60
            GlobalVars.updateWindowPosition = false
            QqAuthorizationManager.revealSettings()
            activity.restartCloudSync()
            settings = WebSettingsSession(activity, loader.createWebSettingsController()) {
                activity.startService(Intent(activity, ControlWindowService::class.java))
                activity.finish()
            }
            activity.requestMediaProjection()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error.message ?: "本地配置初始化失败"
            status = RootStatus.LOCAL_ERROR
            ShowMessage.record("设置初始化失败：$failure", throwable = error)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                status in setOf(RootStatus.ROOT_DENIED, RootStatus.PERMISSION_DENIED, RootStatus.LOCAL_ERROR)
            ) {
                status = RootStatus.CHECKING
                retry++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(settings, activityVisible) {
        if (settings != null && activityVisible) {
            // Keep a strip available when Android backgrounds the launcher activity.
            activity.startService(Intent(activity, ControlWindowService::class.java))
        }
    }
    LaunchedEffect(settings, GlobalVars.isAutoRunEnabled, activityVisible) {
        if (activityVisible) settings?.runCountdown()
    }
    LaunchedEffect(settings, AppStateManager.currentMode, activityVisible) {
        // A Web command can leave settings just as a local Run or Pause button can.
        if (activityVisible && AppStateManager.currentMode == AppMode.Run) settings?.finish()
    }
    BackHandler(enabled = settings != null) {
        settings?.close(play = false, refresh = false)
    }
    SettingsWebPanel(
        surface = "activity",
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        onInteraction = { GlobalVars.isAutoRunEnabled = false },
        beforeFeedback = { activity.moveTaskToBack(true) }
    ) { method, params ->
        when (method) {
            "getStartupState" -> JsonObject().apply {
                addProperty("ready", settings != null)
                addProperty("status", status.name)
                addProperty("message", failure)
            }
            "retryStartup" -> {
                if (status in setOf(RootStatus.ROOT_DENIED, RootStatus.PERMISSION_DENIED, RootStatus.LOCAL_ERROR)) {
                    status = RootStatus.CHECKING
                    retry++
                }
                JsonObject()
            }
            else -> settings?.request(method, params)
                ?: throw SettingsRequestException(503, "设备设置正在初始化")
        }
    }
}
