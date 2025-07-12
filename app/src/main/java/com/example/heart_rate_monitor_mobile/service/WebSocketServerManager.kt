package com.example.heart_rate_monitor_mobile.service

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class WebSocketServerManager(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    override fun onStart() {
        Log.d("WebSocketServer", "服务器已在端口 $port 启动")
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            Log.d("WebSocketServer", "新的客户端连接: ${it.remoteSocketAddress}")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            Log.d("WebSocketServer", "客户端已断开: ${it.remoteSocketAddress}")
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // 目前不需要处理客户端发来的消息，但保留此方法以备将来扩展
        conn?.let {
            Log.d("WebSocketServer", "收到来自 ${it.remoteSocketAddress} 的消息: $message")
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // 同样，处理二进制消息
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        conn?.let {
            Log.e("WebSocketServer", "客户端 ${it.remoteSocketAddress} 发生错误", ex)
        }
    }

    /**
     * 向所有连接的客户端广播消息。
     * @param message 要发送的字符串消息。
     */
    fun broadcastMessage(message: String) { // <-- 方法已重命名
        connections.forEach { conn ->
            try {
                conn.send(message)
            } catch (e: Exception) {
                Log.e("WebSocketServer", "向客户端 ${conn.remoteSocketAddress} 发送消息失败", e)
            }
        }
    }

    /**
     * 停止服务器并关闭所有连接。
     */
    fun stopServer() {
        stop(1000) // 等待1秒让连接正常关闭
        Log.d("WebSocketServer", "服务器已停止")
    }
}