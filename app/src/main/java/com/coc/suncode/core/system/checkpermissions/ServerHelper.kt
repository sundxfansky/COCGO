package com.coc.suncode.core.system.checkpermissions

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import com.coc.suncode.core.system.daemon.RootServerAuth
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

object ServerHelper {
    private val client = OkHttpClient()

    suspend fun waitForServerResponse(): String? {
        val request = Request.Builder().url("ws://localhost:6839/sun").header("Authorization", "Bearer ${RootServerAuth.token}").build()
        val deferred = CompletableDeferred<String?>()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Use the server's explicit readiness probe, not an unsupported system action.
                if (!webSocket.send("""{"actionType":"connection_test"}""")) {
                    deferred.complete(null)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ready = runCatching {
                    val response = JsonParser.parseString(text).asJsonObject
                    response.get("status")?.asString == "success" &&
                        response.get("data")?.asString == "connected"
                }.getOrDefault(false)
                deferred.complete(text.takeIf { ready })
                webSocket.close(1000, "Done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.complete(null)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                deferred.complete(null)
                webSocket.close(code, reason)
            }
        })

        return try {
            withTimeoutOrNull(10000) { deferred.await() }
        } finally {
            // Release the probe even when the screen is disposed or the handshake times out.
            socket.cancel()
        }
    }
}
