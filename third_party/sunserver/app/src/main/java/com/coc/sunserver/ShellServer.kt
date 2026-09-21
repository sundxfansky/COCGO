package com.coc.sunserver

import com.coc.sunserver.command_handlers.FileHandler
import com.coc.sunserver.command_handlers.TouchHandler
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import java.security.MessageDigest
import com.coc.sunserver.command_handlers.ShellHandler
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object ShellServer {

    private const val CLIENT_TARGET_URL = "ws://localhost:16839/sun"
    private const val CLIENT_RETRY_DELAY_MS = 2000L

    @JvmStatic
    fun main(args: Array<String>) {
        // Refuse to expose root commands without a credential supplied by the host.
        val token = System.getenv("SUN_SERVER_TOKEN")?.takeIf { it.length >= 32 }
            ?: error("SUN_SERVER_TOKEN is required")

        val httpClient = HttpClient(ClientCIO) {
            install(ClientWebSockets)
        }

        runBlocking {
            // Launch the embedded WebSocket/HTTP server in a concurrent coroutine
            launch {
                embeddedServer(CIO, host = "127.0.0.1", port = System.getenv("SUN_SERVER_PORT")?.toIntOrNull() ?: 6839) {
                    install(WebSockets)
                    intercept(ApplicationCallPipeline.Plugins) {
                        val supplied = call.request.headers["Authorization"].orEmpty()
                        if (!MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                    routing {
                        // Keep the host's WebSocket protocol alongside the HTTP endpoint.
                        webSocket("/sun") {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val response = withContext(Dispatchers.IO) { dispatchClientMessage(frame.readText()) }
                                    if (response != null) {
                                        send(Frame.Text(response))
                                    }
                                }
                            }
                        }
                        // HTTP GET endpoint: all command parameters are passed as URL query parameters.
                        // Example: GET /sun?actionType=connection_test
                        get("/sun") {
                            val params = call.request.queryParameters

                            // Build a JsonObject from all query parameters so existing handlers can reuse it directly
                            val jsonObject = JsonObject()
                            params.names().forEach { name ->
                                jsonObject.addProperty(name, params[name])
                            }

                            val actionType = params["actionType"]
                            val responseJson = when (actionType) {
                                "connection_test" -> {
                                    val response = JsonObject()
                                    response.addProperty("status", "success")
                                    response.addProperty("data", "connected")
                                    response.toString()
                                }
                                "file_action" -> FileHandler.handle(jsonObject.toString())
                                else -> TouchHandler.handle(jsonObject.toString())
                            }

                            call.respondText(responseJson, ContentType.Application.Json)
                        }
                    }
                }.start(wait = true)
            }

            // Launch the outbound WebSocket client in a concurrent coroutine
            launch {
                // Outbound control is opt-in and uses the same authenticated protocol.
                if (System.getenv("SUN_CONTROLLER_URL") != null) connectAsClient(httpClient)
            }
        }

        httpClient.close()
    }

    /**
     * Continuously attempts to connect to [CLIENT_TARGET_URL] as a WebSocket client.
     * Retries every [CLIENT_RETRY_DELAY_MS] ms on failure. Sends an announcement
     * message on the first successful connection, then dispatches incoming server
     * messages to the existing action handlers.
     */
    private suspend fun connectAsClient(client: HttpClient) {
        var firstConnection = true

        while (true) {
            try {
                println("正在尝试连接到 $CLIENT_TARGET_URL ...")

                client.webSocket(System.getenv("SUN_CONTROLLER_URL") ?: CLIENT_TARGET_URL, request = { header("Authorization", "Bearer ${System.getenv("SUN_SERVER_TOKEN")}") }) {
                    println("已成功连接到 $CLIENT_TARGET_URL")

                    // Send the announcement message only on the very first connection
                    if (firstConnection) {
                        firstConnection = false
                        val announcement = JsonObject().apply {
                            addProperty("actionType", "client_connected")
                            addProperty("message", "SUNserver connected")
                        }
                        send(Frame.Text(announcement.toString()))
                        println("已发送连接公告消息")
                    }

                    // Receive and dispatch messages sent by the remote server
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val response = withContext(Dispatchers.IO) { dispatchClientMessage(text) }
                            if (response != null) {
                                send(Frame.Text(response))
                            }
                        }
                    }

                    println("与 $CLIENT_TARGET_URL 的连接已断开")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("客户端连接失败: ${e.message}，${CLIENT_RETRY_DELAY_MS}ms 后重试...")
            }

            delay(CLIENT_RETRY_DELAY_MS)
        }
    }

    /**
     * Parses an incoming JSON message from the remote server and routes it to
     * the appropriate handler. Returns the JSON response string, or null if the
     * message could not be parsed.
     */
    private fun dispatchClientMessage(text: String): String? {
        return try {
            val jsonElement = JsonParser.parseString(text)
            if (!jsonElement.isJsonObject) return buildClientError("无效的 JSON 消息")

            when (jsonElement.asJsonObject.getAsJsonPrimitive("actionType")?.asString) {
                "connection_test" -> JsonObject().apply {
                    addProperty("status", "success")
                    addProperty("data", "connected")
                }.toString()
                "shell_action" -> ShellHandler.handle(jsonElement.asJsonObject)
                "file_action" -> FileHandler.handle(text)
                else -> TouchHandler.handle(text)
            }
        } catch (e: Exception) {
            println("处理客户端消息时出错: ${e.message}")
            buildClientError("处理消息时出错: ${e.message}")
        }
    }

    private fun buildClientError(message: String): String =
        JsonObject().apply {
            addProperty("status", "error")
            addProperty("message", message)
        }.toString()
}
