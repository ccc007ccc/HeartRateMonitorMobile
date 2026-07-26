package com.example.heart_rate_monitor_mobile.service.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 内置 WebSocket 服务器。安全模型与 [HttpServerManager] 一致：
 * 默认仅绑定 127.0.0.1；对局域网开放时在 HTTP 升级前强制校验 token。
 */
class WebSocketServerManager(
    private val hostname: String,
    private val port: Int,
    private val allowLan: Boolean,
    private val authToken: String,
    private val stateFlow: SharedFlow<String>,
) {
    private var server: AppWebSocketServer? = null

    fun start() {
        if (server != null) return
        try {
            server = AppWebSocketServer().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "WebSocket Server started on $hostname:$port (LAN=${allowLan})")
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket Server start failed on $hostname:$port", e)
            server = null
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "WebSocket Server stopped")
    }

    private inner class AppWebSocketServer : NanoWSD(hostname, port) {

        /** 在升级为 WebSocket 之前完成认证，未授权连接直接 401 */
        override fun serve(session: IHTTPSession): Response {
            if (!HttpServerManager.isAuthorized(session, allowLan, authToken)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized"
                )
            }
            return super.serve(session)
        }

        override fun openWebSocket(handshake: IHTTPSession): WebSocket = AppWebSocket(handshake)

        inner class AppWebSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
            private val webSocketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            override fun onOpen() {
                Log.d(TAG, "WebSocket opened for: ${handshakeRequest.remoteIpAddress}")

                // 心跳保活
                webSocketScope.launch {
                    try {
                        while (isOpen) {
                            delay(PING_INTERVAL_MS)
                            ping(byteArrayOf())
                        }
                    } catch (_: CancellationException) {
                        // 连接关闭时的正常路径
                    } catch (e: IOException) {
                        Log.w(TAG, "Ping 失败，关闭连接", e)
                        close(CloseCode.GoingAway, "Ping failed", false)
                    }
                }

                // 状态推送
                webSocketScope.launch {
                    stateFlow.collect { stateJson ->
                        try {
                            send(stateJson)
                        } catch (e: IOException) {
                            Log.w(TAG, "推送失败，关闭连接", e)
                            close(CloseCode.GoingAway, "Send failed", false)
                        }
                    }
                }
            }

            override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                webSocketScope.cancel()
                Log.d(TAG, "WebSocket closed. Reason: $reason, Remote: $initiatedByRemote")
            }

            override fun onMessage(message: WebSocketFrame) = Unit

            override fun onPong(pong: WebSocketFrame?) = Unit

            override fun onException(exception: IOException) {
                webSocketScope.cancel()
                Log.w(TAG, "WebSocket exception", exception)
            }
        }
    }

    private companion object {
        const val TAG = "WebSocketServerManager"
        const val PING_INTERVAL_MS = 4000L
    }
}
