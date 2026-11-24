package com.coc.suncode.core.ui.floatingwindows

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.qq.QqAuthorizationManager
import com.coc.suncode.core.util.exit.AppExitHelper.restoreDefaultInputMethod
import com.coc.suncode.interfaces.AccountSwitchState
import com.coc.suncode.interfaces.RuntimeAccountSwitcher
import com.coc.suncode.statehelper.AppMode
import com.coc.suncode.statehelper.AppStateManager
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The floating toolbar and both Web surfaces share the same account-switch operation. */
internal object RuntimeControls {
    var isSwitchingAccount by mutableStateOf(false)
        private set

    private val ready get() = GlobalVars.isConfigLoaded && GlobalVars.pluginUI != null
    private val blocked get() = isSwitchingAccount || GlobalVars.isAdPlaying ||
        QqAuthorizationManager.status.value.busy || AppStateManager.currentMode == AppMode.BugReport

    private fun requireAvailable() {
        check(ready) { "客户端正在初始化" }
        check(!blocked) { "客户端正在处理其他操作" }
    }

    suspend fun status() = withContext(Dispatchers.Main.immediate) {
        val switcher = (GlobalVars.pluginUI as? RuntimeAccountSwitcher)
            ?.takeIf { ready && !blocked && !GlobalVars.isSwitchingAccount }
        val accounts = if (switcher == null) AccountSwitchState() else try {
            withContext(Dispatchers.IO) { switcher.accountSwitchState() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed save lookup should not hide the device or disable its playback controls.
            AccountSwitchState()
        }
        JsonObject().apply {
            addProperty("ready", ready)
            addProperty("playing", GlobalVars.isPlaying.value)
            addProperty("autoRun", GlobalVars.isAutoRunEnabled)
            addProperty("busy", blocked)
            if (!blocked && !GlobalVars.isSwitchingAccount && GlobalVars.pluginUI === switcher) {
                accounts.previousAccount?.let { addProperty("previousAccount", it) }
                accounts.nextAccount?.let { addProperty("nextAccount", it) }
            }
        }
    }

    suspend fun start(context: Context) = withContext(Dispatchers.Main.immediate) {
        requireAvailable()
        enterRunMode(context, play = true)
    }

    suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        requireAvailable()
        pausePlayback()
        withContext(Dispatchers.IO) { restoreDefaultInputMethod() }
    }

    suspend fun prepareExit() = withContext(Dispatchers.Main.immediate) {
        requireAvailable()
        pausePlayback()
        BotRuntime.stop()
    }

    suspend fun switchAccount(account: Int, context: Context? = null) = withContext(Dispatchers.Main.immediate) {
        requireAvailable()
        check(!GlobalVars.isSwitchingAccount) { "客户端正在切换账号，请稍后重试" }
        require(account in 1..10_000) { "账号序号无效" }
        val switcher = GlobalVars.pluginUI as? RuntimeAccountSwitcher ?: error("当前插件不支持快速切号")
        // Recheck the current neighbours before claiming the shared operation and stopping gameplay.
        val accounts = withContext(Dispatchers.IO) { switcher.accountSwitchState() }
        requireAvailable()
        check(GlobalVars.pluginUI === switcher && !GlobalVars.isSwitchingAccount &&
            account in listOf(accounts.previousAccount, accounts.nextAccount)) { "相邻账号已变化或存档不存在，请重试" }
        isSwitchingAccount = true
        pausePlayback()
        try {
            BotRuntime.stop()
            // The previous bot may have released its own login flag while stopping.
            GlobalVars.isSwitchingAccount = true
            val switched = withContext(Dispatchers.IO) {
                restoreDefaultInputMethod()
                switcher.switchToAccount(account)
            }
            check(switched) { "切号失败，请查看设备日志。辅助保持暂停。" }
            if (context != null) enterRunMode(context, play = false)
        } finally {
            GlobalVars.isSwitchingAccount = false
            isSwitchingAccount = false
        }
    }

    private fun pausePlayback() {
        GlobalVars.isAutoRunEnabled = false
        GlobalVars.isPlaying.value = false
    }

    private fun enterRunMode(context: Context, play: Boolean) {
        // Start the lifecycle owner before leaving settings so its collector can launch the bot.
        context.startService(Intent(context, ControlWindowService::class.java))
        GlobalVars.isAutoRunEnabled = false
        GlobalVars.isPlaying.value = play
        AppStateManager.setMode(AppMode.Run)
    }
}
