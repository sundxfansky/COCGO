package com.coc.suncode.core.util.basic

import android.content.Context
import android.util.Log
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.floatingwindows.AdItem
import com.coc.suncode.core.ui.floatingwindows.MessageBoxHelper
import com.coc.suncode.core.ui.floatingwindows.MessageBoxHelper.showFloatingMessage
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.StateFlow

object ShowMessage {
    val logLines: StateFlow<List<LogEntry>> = LogHistory.logLines
    private val messageLock = Any()
    private var contextRef: WeakReference<Context>? = null
    private var lastMessage: String = ""
    private var lastShowTime: Long = 0L
    @Volatile
    private var isLogOverlayVisible = false

    fun init(context: Context) {
        this.contextRef = WeakReference(context.applicationContext)
        LogHistory.ensureInstalled()
    }

    fun getContext(): Context? = contextRef?.get()

    /** Records diagnostics without creating a transient message window. */
    fun record(
        text: String,
        priority: Int = Log.DEBUG,
        tag: String = "sun_debug",
        throwable: Throwable? = null
    ) {
        // Preserve early startup events even before application logging is initialized.
        LogHistory.ensureInstalled()
        Timber.tag(tag).log(priority, throwable, text)
    }

    fun clearLogs() {
        LogHistory.clear()
    }

    fun setLogOverlayVisible(visible: Boolean) {
        isLogOverlayVisible = visible
        if (visible) MessageBoxHelper.dismissFloatingMessage()
    }

    fun isLogWindowVisible(): Boolean = isLogOverlayVisible

    operator fun invoke(text: String, isChecking: Boolean = true) {
        // Pausing only suppresses transient presentation; it must not discard log events.
        record(text, priority = Log.VERBOSE)
        if (isChecking && !GlobalVars.isPlaying.value) {
            return
        }
        if (isLogOverlayVisible) return
        val shouldShow = synchronized(messageLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastShowTime
            val result = elapsed >= 100 && !(text == lastMessage && elapsed < 500)
            lastMessage = text
            lastShowTime = now
            result
        }
        contextRef?.get()?.let { context ->
            // Throttle only the transient message, preserving every event in history.
            if (shouldShow) {
                showFloatingMessage(context = context, text = text)
            }
        } ?: Timber.tag("sun_debug").e("ShowMessage: Context not initialized or released, skipping message display")
    }

    // Show the ad overlay with clickable links via MessageBoxService
    fun showAdOverlay(adItems: List<AdItem>, durationSeconds: Int) {
        contextRef?.get()?.let { context ->
            MessageBoxHelper.showAdOverlay(context, adItems, durationSeconds)
        } ?: Timber.tag("sun_debug").e("ShowMessage: Context not initialized or released, skipping ad overlay")
    }

    // Dismiss the ad overlay
    fun dismissAdOverlay() {
        MessageBoxHelper.dismissAdOverlay()
    }
}
