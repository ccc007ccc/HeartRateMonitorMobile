package com.example.heart_rate_monitor_mobile.service.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException

/**
 * 内置 HTTP 服务器。
 *
 * 安全模型：
 * - [allowLan] 为 false（默认）时仅绑定 127.0.0.1，心率数据不出本机；
 * - 为 true 时绑定所有网卡，且强制校验 [authToken]
 *   （`Authorization: Bearer <token>` 或 `?token=<token>`）。
 */
class HttpServerManager(
    private val hostname: String,
    private val port: Int,
    private val allowLan: Boolean,
    private val authToken: String,
    private val snapshotProvider: () -> JSONObject,
) {
    private var server: HttpServer? = null

    fun start() {
        if (server != null) return
        try {
            server = HttpServer().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "HTTP Server started on $hostname:$port (LAN=${allowLan})")
        } catch (e: IOException) {
            Log.e(TAG, "HTTP Server start failed on $hostname:$port", e)
            server = null
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTP Server stopped")
    }

    private inner class HttpServer : NanoHTTPD(hostname, port) {
        override fun serve(session: IHTTPSession?): Response {
            session ?: return notFound()
            if (!isAuthorized(session, allowLan, authToken)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized"
                )
            }
            if (session.method == Method.GET && session.uri == "/heartrate") {
                return newFixedLengthResponse(
                    Response.Status.OK, "application/json", snapshotProvider().toString()
                )
            }
            return notFound()
        }

        private fun notFound() =
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    companion object {
        private const val TAG = "HttpServerManager"

        /** 仅本机访问时免认证；对局域网开放时强制 token */
        fun isAuthorized(session: NanoHTTPD.IHTTPSession, allowLan: Boolean, token: String): Boolean {
            if (!allowLan) return true
            if (token.isEmpty()) return false
            val header = session.headers["authorization"]
            if (header != null && header.equals("Bearer $token", ignoreCase = false)) return true
            val queryToken = session.parameters["token"]?.firstOrNull()
            return queryToken == token
        }
    }
}
