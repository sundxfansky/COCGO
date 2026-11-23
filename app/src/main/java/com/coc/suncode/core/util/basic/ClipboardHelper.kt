package com.coc.suncode.core.util.basic

import android.content.ClipboardManager
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ClipboardText(val text: String, val timestampMillis: Long?)

/** Bridges the focus requirement for clipboard reads on Android 10 and later. */
object ClipboardHelper {
    private val readLock = Mutex()
    private var nextId = 0L
    private var request: Pair<Long, CompletableDeferred<ClipboardText?>>? = null
    internal const val REQUEST_ID = "request_id"
    internal const val FOCUS_TIMEOUT_MS = 10_000L

    fun isAvailable(): Boolean {
        val context = ShowMessage.getContext() ?: return false
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(ComponentName(context, ClipboardReadActivity::class.java), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun readClipboard(): ClipboardText? = readLock.withLock {
        val context = ShowMessage.getContext() ?: return@withLock null
        withContext(Dispatchers.Main.immediate) {
            val id = ++nextId
            val reply = CompletableDeferred<ClipboardText?>()
            request = id to reply
            try {
                context.startActivity(Intent(context, ClipboardReadActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(REQUEST_ID, id)
                })
                withTimeoutOrNull(FOCUS_TIMEOUT_MS) { reply.await() }
            } catch (error: SecurityException) {
                ShowMessage.record("Clipboard read activity could not start", throwable = error)
                null
            } finally {
                request = null
            }
        }
    }

    internal fun finishRead(id: Long, clipboard: ClipboardManager?) {
        val pending = request?.takeIf { it.first == id }?.second ?: return
        val result = try {
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.let { clip ->
                val text = clip.getItemAt(0).text?.toString() ?: return@let null
                val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clip.description.timestamp else null
                ClipboardText(text, timestamp)
            }
        } catch (error: SecurityException) {
            ShowMessage.record("Clipboard read was denied", throwable = error)
            null
        }
        pending.complete(result)
    }
}
