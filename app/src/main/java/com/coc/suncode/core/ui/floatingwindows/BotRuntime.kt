package com.coc.suncode.core.ui.floatingwindows

import com.coc.suncode.core.util.touchactions.TouchActions
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.system.input.TouchInputException
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.waitForPlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared stop barrier used by service transitions and plugin replacement. */
object BotRuntime {
    @Volatile var job: Job? = null
    private val stopMutex = Mutex()
    suspend fun start(scope: CoroutineScope, run: suspend () -> Unit) = stopMutex.withLock {
        if (job?.isActive == true) return@withLock
        // Join cleanup from a destroyed service before a replacement can issue touches.
        job?.join()
        TouchActions.releaseAllPointers()
        job = scope.launch(Dispatchers.IO) {
            ShowMessage.record("辅助任务已启动：configLoaded=${GlobalVars.isConfigLoaded}，plugin=${GlobalVars.pluginUI?.javaClass?.name}")
            try {
                while (isActive) {
                    try {
                        run()
                        ShowMessage.record("辅助脚本已正常返回")
                        break
                    } catch (error: TouchInputException) {
                        // Resume from the script entry after a rejected gesture, never halfway through it.
                        GlobalVars.isPlaying.value = false
                        TouchActions.releaseAllPointers()
                        ShowMessage.record("目标应用触摸失败，辅助已暂停：${error.message}", throwable = error)
                        ShowMessage("目标应用触摸失败，辅助已暂停：${error.message}", isChecking = false)
                        waitForPlay()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (error is VirtualMachineError || error is ThreadDeath) throw error
                        // Keep a plugin/runtime exception from silently cancelling the bot coroutine.
                        // The next run starts at the script entry after the user resumes playback.
                        GlobalVars.isPlaying.value = false
                        TouchActions.releaseAllPointers()
                        val reason = "${error::class.java.simpleName}: ${error.message ?: "无错误信息"}"
                        ShowMessage.record("辅助执行异常，已暂停：$reason", throwable = error)
                        ShowMessage("辅助执行异常，已暂停：$reason", isChecking = false)
                        waitForPlay()
                    }
                }
            } finally {
                // Service destruction may interrupt a long press between protocol requests.
                withContext(NonCancellable) {
                    TouchActions.releaseAllPointers()
                }
                ShowMessage.record("辅助任务已结束，触点已释放")
            }
        }
    }
    suspend fun stop() = stopMutex.withLock {
        ShowMessage.record("请求停止辅助任务：active=${job?.isActive == true}")
        job?.cancelAndJoin()
        job = null
        TouchActions.releaseAllPointers()
    }
}
