package com.example.heart_rate_monitor_mobile.service.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException

class WebSocketServerManager(
    private val port: Int,
    private val stateFlow: SharedFlow<String>
) {
    private var server: AppWebSocketServer? = null

    fun start() {
        if (server == null) {
            try {
                server = AppWebSocketServer()
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d("WebSocketServerManager", "WebSocket Server started on port $port")
            } catch (e: Exception) {
                Log.e("WebSocketServerManager", "WebSocket Server start failed", e)
            }
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.d("WebSocketServerManager", "WebSocket Server stopped")
    }

    private inner class AppWebSocketServer : NanoWSD(port) {
        override fun openWebSocket(handshake: IHTTPSession): WebSocket {
            return AppWebSocket(handshake)
        }

        inner class AppWebSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
            private val webSocketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            override fun onOpen() {
                Log.d("AppWebSocket", "WebSocket opened for: ${handshakeRequest.remoteIpAddress}")

                // Coroutine for handling heartbeats (Ping/Pong)
                webSocketScope.launch {
                    try {
                        while (isOpen) {
                            delay(4000)
                            ping(byteArrayOf())
                        }
                    } catch (e: CancellationException) {
                        // This is expected when the scope is cancelled
                    } catch (e: IOException) {
                        Log.e("AppWebSocket", "Error sending ping, closing connection.", e)
                        close(CloseCode.GoingAway, "Ping failed", false)
                    }
                }

                // Coroutine for listening to state updates and sending them to the client
                webSocketScope.launch {
                    stateFlow.collect { stateJson ->
                        try {
                            send(stateJson)
                        } catch (e: IOException) {
                            Log.e("AppWebSocket", "Failed to send state update, closing connection.", e)
                            close(CloseCode.GoingAway, "Send failed", false)
                        }
                    }
                }
            }

            override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                webSocketScope.cancel()
                Log.d("AppWebSocket", "WebSocket closed. Reason: $reason, Remote: $initiatedByRemote")
            }

            override fun onMessage(message: WebSocketFrame) {
                // Not used
            }

            override fun onPong(pong: WebSocketFrame?) {
                // Pong received, connection is alive
            }

            override fun onException(exception: IOException) {
                webSocketScope.cancel()
                Log.e("AppWebSocket", "WebSocket exception", exception)
            }
        }
    }
}