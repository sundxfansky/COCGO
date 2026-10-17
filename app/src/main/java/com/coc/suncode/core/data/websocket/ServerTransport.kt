package com.coc.suncode.core.data.websocket

/** Stable plugin boundary; production uses private local pipes, with no network listener. */
interface ServerTransport {
    fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit)
    fun sendAction(action: Any): Boolean
    fun close()
}
