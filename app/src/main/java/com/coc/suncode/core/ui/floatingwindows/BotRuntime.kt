package com.coc.suncode.core.ui.floatingwindows

import com.coc.suncode.core.util.touchactions.TouchActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
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
        job = scope.launch(Dispatchers.IO) { run() }
    }
    suspend fun stop() = stopMutex.withLock {
        job?.cancelAndJoin()
        job = null
        TouchActions.releaseAllPointers()
    }
}
