package com.coc.suncode.core.qq

import android.content.Context
import android.content.Intent
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.basic.ServerShell
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Stable host API shared by the bundled UI and independently loaded plugins. */
object QqAuthorizationManager {
    data class Status(
        val busy: Boolean = false, val message: String = "", val revision: Long = 0,
        val hideSettings: Boolean = false, val accountSlot: Int? = null
    )
    internal class Request(val id: String, val slot: Int, val login: Boolean, val allowCapture: Boolean = true) {
        val completion = CompletableDeferred<Boolean>()
        var job: Job? = null
        var watchdog: Job? = null
        var resetStarted = false
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Request? = null
    private var clearing = false
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus

    fun launch(context: Context, slot: Int, login: Boolean = false): Boolean {
        val started = start(context, slot, login) != null
        // Manual authorization owns the foreground until the user explicitly resumes automation.
        if (started) GlobalVars.isPlaying.value = false
        return started
    }

    /** Reuse a shared grant without implicitly opening a new capture flow. */
    fun launchSaved(context: Context, slot: Int): Boolean {
        val started = start(context, slot, login = true, allowCapture = false) != null
        if (started) GlobalVars.isPlaying.value = false
        return started
    }

    private fun start(context: Context, slot: Int, login: Boolean, allowCapture: Boolean = true): Request? {
        if (slot !in 1..10_000) {
            ShowMessage("授权序号须为 1 至 10000", isChecking = false)
            return null
        }
        val request = synchronized(lock) {
            if (active != null || clearing) null else Request(UUID.randomUUID().toString(), slot, login, allowCapture).also {
                active = it
                mutableStatus.value = Status(true, "准备 QQ 授权序号 $slot", mutableStatus.value.revision, accountSlot = slot)
            }
        }
        if (request == null) {
            ShowMessage("已有 QQ 授权或切号操作正在进行", isChecking = false)
            return null
        }
        request.watchdog = scope.launch {
            delay(420_000L)
            request.job?.cancel()
            finish(request, false, "QQ 操作已超时，请重新发起")
        }
        try {
            context.startActivity(Intent(context, QqAuthorizationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("request_id", request.id)
                putExtra("slot", request.slot)
                putExtra("login", request.login)
                putExtra("allow_capture", request.allowCapture)
            })
        } catch (_: Exception) {
            finish(request, false, "无法打开 QQ 授权界面，请从前台设置页重试")
            return null
        }
        return request
    }

    /** Automation awaits authorization and an explicit login attempt before entering gameplay. */
    suspend fun loginAccount(context: Context, slot: Int): Boolean {
        // Unattended rotation must not wait for a QR scan when another account can still run.
        val ready = withContext(Dispatchers.IO) {
            runCatching { QqAuthorizationStore(context).read(slot)?.isExpired() == false }.getOrDefault(false)
        }
        if (!ready) {
            ShowMessage("QQ 授权序号 $slot 缺失或已过期，本轮跳过；请在全局授权库中获取授权", isChecking = false)
            return false
        }
        val request = withContext(Dispatchers.Main.immediate) { start(context, slot, true, allowCapture = false) } ?: return false
        try {
            return withTimeout(420_000L) { request.completion.await() }
        } finally {
            if (!request.completion.isCompleted) {
                request.job?.cancel()
                finish(request, false, "QQ 登录等待已取消")
            }
        }
    }

    internal fun restore(id: String, slot: Int, login: Boolean, allowRestore: Boolean, allowCapture: Boolean): Request? = synchronized(lock) {
        if (clearing || slot !in 1..10_000 || id.isBlank()) return@synchronized null
        active?.let {
            return@synchronized it.takeIf { request ->
                request.id == id && request.slot == slot && request.login == login && request.allowCapture == allowCapture
            }
        }
        // An obsolete Activity must not resurrect a canceled request in the same process.
        if (!allowRestore) return@synchronized null
        Request(id, slot, login, allowCapture).also {
            active = it
            mutableStatus.value = Status(true, "恢复 QQ 授权序号 $slot 的操作", mutableStatus.value.revision, accountSlot = slot)
        }
    }

    internal suspend fun execute(
        context: Context,
        request: Request,
        alreadyLaunched: Boolean,
        obtainResult: suspend () -> String?
    ) {
        request.job = currentCoroutineContext()[Job]
        var success = false
        var message = "QQ 授权已取消"
        var stage = "verify_environment"
        try {
            withTimeout(360_000L) {
                val backend = QqToolBackend(context)
                val store = QqAuthorizationStore(context)
                withContext(Dispatchers.IO) {
                    backend.verifyTool()
                    if (request.login) backend.verifyGame()
                }
                // A damaged local record can be replaced by a fresh user authorization.
                var record = if (request.login) withContext(Dispatchers.IO) {
                    runCatching { store.read(request.slot) }.getOrNull()
                } else null
                if (!request.login || record == null || record.isExpired() || alreadyLaunched) {
                    // Recheck after preflight in case a grant was cleared or expired before the bridge opened.
                    if (!request.allowCapture) {
                        message = "QQ 授权序号 ${request.slot} 缺失或已过期，请在全局授权库中重新获取"
                        return@withTimeout
                    }
                    stage = "prepare_capture"
                    if (!alreadyLaunched) withContext(Dispatchers.IO) { backend.prepareCapture() }
                    stage = "await_authorization"
                    update("请在 QQ 授权工具中完成登录，授权保存到全局序号 ${request.slot}")
                    val response = obtainResult() ?: return@withTimeout
                    stage = "save_authorization"
                    // A malformed callback must never replace a previously valid grant.
                    record = QqAuthorizationRecord.fromResponse(response)
                    withContext(Dispatchers.IO) { store.write(request.slot, record) }
                }
                if (request.login) {
                    stage = "prepare_replay"
                    update("正在使用 QQ 授权序号 ${request.slot} 登录国服")
                    withContext(Dispatchers.IO) { backend.prepareReplay(checkNotNull(record)) }
                    request.resetStarted = true
                    stage = "request_game_login"
                    success = withContext(Dispatchers.IO) { QqGameLogin(backend).launchAndRequestLogin(checkNotNull(record)) }
                    message = if (success) "已使用授权序号 ${request.slot} 发起 QQ 登录，请以游戏内结果为准"
                    else "国服已启动，未识别到 QQ 登录入口；请检查游戏画面，自动运行已暂停本次登录"
                } else {
                    success = true
                    message = "QQ 授权已保存到全局序号 ${request.slot}，可在任意账号设置中复用"
                }
            }
        } catch (error: CancellationException) {
            message = "QQ 授权操作已取消或超时"
            throw error
        } catch (error: Exception) {
            // Exception payloads may contain SDK responses; log only a controlled, token-free message.
            ShowMessage.record("QQ 操作失败：stage=$stage，type=${error.javaClass.simpleName}")
            message = when (error) {
                is IOException -> error.message ?: "QQ 授权操作失败"
                else -> "QQ 授权数据或设备环境异常，请重新获取授权"
            }
        } finally {
            withContext(NonCancellable) { finish(request, success, message) }
        }
    }

    private fun update(message: String) {
        mutableStatus.value = mutableStatus.value.copy(message = message)
        ShowMessage(message, isChecking = false)
    }

    private fun finish(request: Request, success: Boolean, message: String) {
        synchronized(lock) {
            request.watchdog?.cancel()
            if (active?.id == request.id) {
                active = null
                mutableStatus.value = Status(
                    false, message, mutableStatus.value.revision + 1,
                    hideSettings = request.resetStarted, accountSlot = request.slot
                )
                ShowMessage(message, isChecking = false)
            }
            request.completion.complete(success)
        }
    }

    internal fun interruptRestoredLogin(request: Request) {
        finish(request, false, "上次 QQ 登录因进程重启而中断，请检查游戏后重新发起；未重复清除游戏数据")
    }

    fun revealSettings() {
        synchronized(lock) {
            if (!mutableStatus.value.busy && mutableStatus.value.hideSettings) {
                mutableStatus.value = mutableStatus.value.copy(hideSettings = false)
            }
        }
    }

    suspend fun summary(context: Context, slot: Int): String = withContext(Dispatchers.IO) {
        try {
            val record = QqAuthorizationStore(context).read(slot)
            "授权序号 $slot：" + when {
                record == null -> "尚未获取 QQ 授权"
                record.isExpired() -> "QQ 授权已过期，请重新获取"
                else -> "已保存 QQ 授权，剩余 ${formatQqAuthorizationTime(record.expiresAt - System.currentTimeMillis())}"
            }
        } catch (_: Exception) {
            "授权序号 $slot：授权记录无法读取，请重新获取"
        }
    }

    suspend fun isToolAtFront(): Boolean {
        val result = ServerShell.exec("dumpsys activity activities")
        return result.isSuccess && result.out.any {
            (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains(QqToolBackend.ACTIVITY)
        }
    }

    suspend fun clearSavedAuthorizations(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(active == null && !clearing) { "请先完成或取消当前 QQ 授权操作" }
            clearing = true
        }
        try {
            // Block new authorization requests until both private grants and companion caches are gone.
            QqAuthorizationStore(context).clear()
            QqToolBackend(context).clearAuthorizationCache()
            mutableStatus.value = Status(revision = mutableStatus.value.revision + 1)
        } finally {
            synchronized(lock) { clearing = false }
        }
    }
}
