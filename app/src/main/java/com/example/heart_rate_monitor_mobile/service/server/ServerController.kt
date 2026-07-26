package com.example.heart_rate_monitor_mobile.service.server

import android.content.Context
import com.example.heart_rate_monitor_mobile.ble.BleStateTexts
import com.example.heart_rate_monitor_mobile.data.settings.ServerSettings
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import com.example.heart_rate_monitor_mobile.domain.HeartRateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * HTTP/WebSocket 服务器生命周期控制器。
 *
 * - 由 BleService 的存活周期 start()/stop()（与旧行为一致：服务器只在心率服务运行时可用）；
 * - 观察设置流，端口 / 开关 / 认证 / token 任一变化即重启对应服务器
 *   （token 纳入配置键保证"重置 token 立即生效、旧 token 立即吊销"）；
 * - 绑定所有网卡（与 v1.x 一致，家庭局域网生态直连）；Token 认证为可选项，默认关闭；
 * - restart/stop 经 synchronized 串行化，杜绝 stop 与配置变更并发导致的服务器泄漏。
 */
class ServerController(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val data: HeartRateRepository,
    private val appContext: Context,
) {
    private var controllerJob: Job? = null
    private var httpServer: HttpServerManager? = null
    private var webSocketServer: WebSocketServerManager? = null

    /** stop() 后置位；synchronized 块内检查，防止在途的配置 collect 复活服务器 */
    @Volatile
    private var stopped = true

    private val wsPayload = MutableSharedFlow<String>(replay = 1)

    private data class ServerConfig(
        val enabled: Boolean,
        val port: Int,
        val authRequired: Boolean,
        val token: String,
    )

    fun start() {
        if (controllerJob?.isActive == true) return
        stopped = false
        controllerJob = scope.launch {
            // 状态推送：固定 1Hz 快照（保持旧版"持续有推送"的活性语义，
            // 纯 combine 在数值不变时不发射，客户端会误判连接死掉）
            launch {
                while (true) {
                    wsPayload.emit(snapshotJson().toString())
                    delay(WS_PUSH_INTERVAL_MS)
                }
            }

            launch {
                settings.flowOf { it.server.httpConfig() }.collect { config ->
                    restartHttp(config)
                }
            }
            launch {
                settings.flowOf { it.server.wsConfig() }.collect { config ->
                    restartWebSocket(config)
                }
            }
        }
    }

    fun stop() {
        controllerJob?.cancel()
        controllerJob = null
        stopped = true
        synchronized(this) {
            httpServer?.stop()
            httpServer = null
            webSocketServer?.stop()
            webSocketServer = null
        }
    }

    private suspend fun restartHttp(config: ServerConfig) {
        val token = ensureToken(config)
        synchronized(this) {
            httpServer?.stop()
            httpServer = null
            if (!stopped && config.enabled) {
                httpServer = HttpServerManager(
                    hostname = BIND_HOST,
                    port = config.port,
                    authRequired = config.authRequired,
                    authToken = token,
                    snapshotProvider = ::snapshotJson,
                ).also { it.start() }
            }
        }
    }

    private suspend fun restartWebSocket(config: ServerConfig) {
        val token = ensureToken(config)
        synchronized(this) {
            webSocketServer?.stop()
            webSocketServer = null
            if (!stopped && config.enabled) {
                webSocketServer = WebSocketServerManager(
                    hostname = BIND_HOST,
                    port = config.port,
                    authRequired = config.authRequired,
                    authToken = token,
                    stateFlow = wsPayload,
                ).also { it.start() }
            }
        }
    }

    private fun snapshotJson(): JSONObject {
        val state = data.bleState.value
        return JSONObject().apply {
            // 既有字段 = 主设备（HeartRateWidget/桌面版契约不变）
            put("heart_rate", data.heartRate.value)
            put("connected", data.isDeviceConnected())
            put("status", BleStateTexts.displayText(appContext, state))
            put("status_key", BleStateTexts.statusKey(state))
            put("timestamp", System.currentTimeMillis())
            put("speed", data.speed.value)
            // 增量字段：全部设备（主 + 对比），供多设备消费方使用
            put("devices", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "primary")
                    put("name", (state as? com.example.heart_rate_monitor_mobile.ble.BleState.Connected)?.deviceName ?: "")
                    put("connected", data.isDeviceConnected())
                    put("heart_rate", data.heartRate.value)
                })
                val devices = data.comparison.devices.value
                val readings = data.comparisonReadings.value
                devices.values.forEach { device ->
                    put(JSONObject().apply {
                        put("id", device.id)
                        put("name", device.name)
                        put("connected", device.connected)
                        put("heart_rate", readings[device.id]?.bpm ?: 0)
                    })
                }
            })
        }
    }

    /**
     * 兜底：开启认证但 token 为空时补生成（正常路径由 ServerActivity 在开启开关前写入；
     * 写入会触发配置流重启服务器，最终一致）。
     */
    private suspend fun ensureToken(config: ServerConfig): String {
        if (!config.authRequired || config.token.isNotEmpty()) return config.token
        val token = generateToken()
        settings.set(SettingsKeys.SERVER_AUTH_TOKEN, token)
        return token
    }

    companion object {
        private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val TOKEN_LENGTH = 24
        private const val WS_PUSH_INTERVAL_MS = 1000L

        fun generateToken(): String {
            val random = SecureRandom()
            return buildString(TOKEN_LENGTH) {
                repeat(TOKEN_LENGTH) { append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)]) }
            }
        }

        /** 与 v1.x 一致绑定所有网卡：同网的 HeartRateWidget / 桌面版可直连 */
        private const val BIND_HOST = "0.0.0.0"

        private fun ServerSettings.httpConfig() = ServerConfig(httpEnabled, httpPort, authRequired, authToken)
        private fun ServerSettings.wsConfig() = ServerConfig(webSocketEnabled, webSocketPort, authRequired, authToken)
    }
}
