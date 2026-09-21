package com.coc.suncode.core.data.websocket

/** Transport boundary keeps request ordering testable without an Android device. */
interface ServerTransport {
    fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit)
    fun sendAction(action: Any): Boolean
    fun close()
}
