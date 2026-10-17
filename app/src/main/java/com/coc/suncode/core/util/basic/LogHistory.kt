package com.coc.suncode.core.util.basic

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class LogEntry(val id: Long, val line: String)

/** Shares every application Timber event with the optional floating log window. */
internal object LogHistory {
    private const val MAX_LOG_ENTRIES = 1000
    private val historyLock = Any()
    private var nextId = 0L
    private val entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logLines: StateFlow<List<LogEntry>> = entries.asStateFlow()

    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val level = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> priority.toString()
            }
            // Timber already appends throwable stacks before dispatching to its trees.
            val line = "$timestamp $level/${tag ?: "sun_debug"}: $message"
            synchronized(historyLock) {
                // Stable IDs keep the reader's position when older events are evicted.
                val entry = LogEntry(++nextId, line)
                entries.value = (entries.value + entry).takeLast(MAX_LOG_ENTRIES)
            }
        }
    }

    @Synchronized
    fun ensureInstalled() {
        // Repeated activity/service initialization must never duplicate history entries.
        if (tree !in Timber.forest()) Timber.plant(tree)
    }

    fun clear() {
        synchronized(historyLock) {
            entries.value = emptyList()
        }
    }
}
