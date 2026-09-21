package com.coc.suncode.core.data.websocket

import com.google.gson.Gson
import okhttp3.OkHttpClient
import com.coc.suncode.core.system.daemon.RootServerAuth
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ServerConnection(private val url: String) : ServerTransport {
    private companion object {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private val gson = Gson()
    private val generation = AtomicLong()
    @Volatile private var webSocket: WebSocket? = null

    @Synchronized
    override fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        close()
        val current = generation.get()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${RootServerAuth.token}").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@ServerConnection) {
                    if (generation.get() != current) {
                        webSocket.cancel()
                        return
                    }
                    // Publish the socket before startup requests are sent by the callback.
                    this@ServerConnection.webSocket = webSocket
                }
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() == current) onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation.get() == current) onFailure(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (generation.get() == current) onFailure(IOException("WebSocket closed: $code / $reason"))
            }
        })
    }

    override fun sendAction(action: Any): Boolean = webSocket?.send(gson.toJson(action)) ?: false

    @Synchronized
    override fun close() {
        // Invalidate callbacks and discard queued commands when recovering from a timeout.
        generation.incrementAndGet()
        webSocket?.cancel()
        webSocket = null
    }
}
