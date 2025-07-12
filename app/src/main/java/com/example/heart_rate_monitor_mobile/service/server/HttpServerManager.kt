package com.example.heart_rate_monitor_mobile.service.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException

class HttpServerManager(
    private val port: Int,
    private val heartRateFlow: StateFlow<Int>,
    private val isDeviceConnected: () -> Boolean
) {
    private var server: HttpServer? = null

    fun start() {
        if (server == null) {
            try {
                server = HttpServer()
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d("HttpServerManager", "HTTP Server started on port $port")
            } catch (e: IOException) {
                Log.e("HttpServerManager", "HTTP Server start failed", e)
            }
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.d("HttpServerManager", "HTTP Server stopped")
    }

    private inner class HttpServer : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession?): Response {
            if (session?.method == Method.GET && session.uri == "/heartrate") {
                val json = JSONObject().apply {
                    put("heart_rate", heartRateFlow.value)
                    put("connected", isDeviceConnected())
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}