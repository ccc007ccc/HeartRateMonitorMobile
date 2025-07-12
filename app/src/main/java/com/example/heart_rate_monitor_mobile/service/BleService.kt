package com.example.heart_rate_monitor_mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleManager
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import com.example.heart_rate_monitor_mobile.ui.webhook.WebhookManager
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.peripheral
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class BleService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager
    private lateinit var webhookManager: WebhookManager
    private lateinit var sharedPreferences: SharedPreferences

    // --- StateFlow for modern UI state management ---
    private val _bleState = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _scanResults = MutableStateFlow<List<Advertisement>>(emptyList())
    val scanResults: StateFlow<List<Advertisement>> = _scanResults.asStateFlow()

    // --- State Management ---
    private var connectedPeripheral: Peripheral? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServerManager? = null
    @Volatile private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)

    private var lastConnectedDeviceId: String? = null

    fun isDeviceConnected(): Boolean = connectedPeripheral?.state?.value is State.Connected

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(applicationContext)
        webhookManager = WebhookManager(applicationContext)
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        startForegroundService()
        registerSettingsListener()

        if (sharedPreferences.getBoolean("http_server_enabled", false)) {
            startHttpServer()
        }
        if (sharedPreferences.getBoolean("websocket_server_enabled", false)) {
            startWebSocketServer()
        }
    }

    private fun startForegroundService() {
        val channelId = "BleServiceChannel"
        val channelName = "BLE 连接状态"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("心率监控器")
            .setContentText("服务正在后台运行以保持蓝牙连接")
            .setSmallIcon(R.drawable.ic_bluetooth_connected)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun startScan(durationMillis: Long = 15_000L) {
        if (!isScanning.compareAndSet(false, true)) return
        stopAllBleActivities()

        scanJob = serviceScope.launch {
            val foundDevices = mutableSetOf<Advertisement>()
            try {
                _bleState.value = BleState.Scanning
                withTimeout(durationMillis) {
                    bleManager.scan().collect { advertisement ->
                        if (foundDevices.none { it.identifier == advertisement.identifier }) {
                            foundDevices.add(advertisement)
                            _scanResults.value = foundDevices.toList()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Expected timeout
            } finally {
                withContext(NonCancellable) {
                    val statusMessage = if (foundDevices.isNotEmpty()) "扫描结束" else "未找到任何设备"
                    _bleState.value = BleState.ScanFailed(statusMessage)
                    isScanning.set(false)
                }
            }
        }
    }

    fun startAutoConnectScan(favoriteDeviceId: String, durationMillis: Long = 15_000L) {
        if (!isScanning.compareAndSet(false, true)) return
        stopAllBleActivities()

        scanJob = serviceScope.launch {
            val foundDevices = mutableSetOf<Advertisement>()
            var favoriteFound = false
            if (_bleState.value !is BleState.AutoReconnecting) {
                _bleState.value = BleState.AutoConnecting
            }

            try {
                withTimeout(durationMillis) {
                    bleManager.scan().collect { advertisement ->
                        if (foundDevices.none { it.identifier == advertisement.identifier }) {
                            foundDevices.add(advertisement)
                            _scanResults.value = foundDevices.toList()
                        }

                        if (advertisement.identifier == favoriteDeviceId) {
                            favoriteFound = true
                            this.coroutineContext.job.cancel()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore timeout or cancellation
            } finally {
                withContext(NonCancellable) {
                    isScanning.set(false)
                    if (favoriteFound) {
                        connectToDevice(favoriteDeviceId)
                    } else {
                        if (_bleState.value is BleState.AutoConnecting || _bleState.value is BleState.AutoReconnecting) {
                            _bleState.value = BleState.ScanFailed("自动连接失败: 未找到设备")
                        }
                    }
                }
            }
        }
    }


    fun connectToDevice(identifier: String) {
        stopAllBleActivities()
        isManuallyDisconnected = false

        connectionJob = serviceScope.launch {
            var peripheral: Peripheral? = null
            try {
                peripheral = serviceScope.peripheral(identifier)
                connectedPeripheral = peripheral
                lastConnectedDeviceId = identifier

                if (_bleState.value !is BleState.AutoReconnecting) {
                    _bleState.value = BleState.Connecting
                }

                val stateMonitor = launch {
                    peripheral.state
                        .filter { it !is State.Disconnected || it.status != null }
                        .collect { state ->
                            Log.d("BleService", "Filtered Connection State Changed: $state")
                            when (state) {
                                is State.Connecting -> {
                                    if (_bleState.value !is BleState.AutoReconnecting) {
                                        _bleState.value = BleState.Connecting
                                    }
                                }
                                is State.Connected -> {
                                    val msg = "已连接到 ${peripheral.name ?: "未知设备"}"
                                    _bleState.value = BleState.Connected(msg)
                                    webhookManager.triggerWebhooks(WebhookTrigger.CONNECTED)
                                    broadcastWebSocketState()
                                    launch { observeHeartRateData(peripheral) }
                                }
                                is State.Disconnecting -> _bleState.value = BleState.Disconnected("正在断开...")
                                is State.Disconnected -> {
                                    this@launch.cancel(CancellationException("Device disconnected with status: ${state.status}"))
                                }
                            }
                        }
                }

                withTimeout(20_000L) {
                    peripheral.connect()
                }
                stateMonitor.join()

            } catch (e: Exception) {
                val errorMessage = when (e) {
                    is TimeoutCancellationException -> "连接超时"
                    is CancellationException -> "连接已取消: ${e.message}"
                    else -> "连接失败: ${e.message}"
                }
                Log.e("BleService", "Connection to $identifier failed", e)
                if (_bleState.value !is BleState.AutoReconnecting) {
                    _bleState.value = BleState.Disconnected(errorMessage)
                }

            } finally {
                withContext(NonCancellable) {
                    try {
                        peripheral?.disconnect()
                    } catch (e: Exception) {
                        Log.w("BleService", "Error during disconnect in finally block", e)
                    }

                    cleanupConnection()

                    val autoReconnectEnabled = sharedPreferences.getBoolean("auto_reconnect_enabled", true)
                    if (autoReconnectEnabled && !isManuallyDisconnected && lastConnectedDeviceId != null) {
                        Log.d("BleService", "自动重连已触发，目标: $lastConnectedDeviceId")
                        delay(1000)
                        _bleState.value = BleState.AutoReconnecting
                        startAutoConnectScan(lastConnectedDeviceId!!)
                    }
                }
            }
        }
    }

    fun disconnectDevice() {
        isManuallyDisconnected = true
        stopAllBleActivities()
    }

    private fun stopAllBleActivities() {
        scanJob?.cancel()
        connectionJob?.cancel()
        _scanResults.value = emptyList()
    }

    private fun cleanupConnection() {
        val message = if (isManuallyDisconnected) "已手动断开" else "设备连接已断开"
        _heartRate.value = 0
        if (_bleState.value !is State.Connected) {
            _bleState.value = BleState.Disconnected(message)
        }
        webhookManager.triggerWebhooks(WebhookTrigger.DISCONNECTED)
        broadcastWebSocketState()

        connectedPeripheral = null
    }

    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        try {
            bleManager.observeHeartRate(peripheral).collect { rate ->
                if (rate != _heartRate.value) {
                    _heartRate.value = rate
                    webhookManager.triggerWebhooks(WebhookTrigger.HEART_RATE_UPDATED, rate)
                    broadcastWebSocketState()
                }
            }
        } catch (e: Exception) {
            Log.w("BleService", "Heart rate observation stopped or failed.", e)
        }
    }

    private fun broadcastWebSocketState() {
        if (webSocketServer == null || !sharedPreferences.getBoolean("websocket_server_enabled", false)) return

        val json = JSONObject().apply {
            put("heart_rate", _heartRate.value)
            put("connected", isDeviceConnected())
            put("status", _bleState.value.message)
            put("timestamp", System.currentTimeMillis())
        }
        webSocketServer?.broadcastMessage(json.toString()) // <-- 已更新调用
    }

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "http_server_enabled" -> if (prefs.getBoolean(key, false)) startHttpServer() else stopHttpServer()
            "http_server_port" -> if (prefs.getBoolean("http_server_enabled", false)) restartHttpServer()
            "websocket_server_enabled" -> if (prefs.getBoolean(key, false)) startWebSocketServer() else stopWebSocketServer()
            "websocket_server_port" -> if (prefs.getBoolean("websocket_server_enabled", false)) restartWebSocketServer()
        }
    }

    private fun registerSettingsListener() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
    }

    private fun startHttpServer() {
        if (httpServer == null) {
            val port = sharedPreferences.getInt("http_server_port", 8000)
            try {
                httpServer = HttpServer(port)
                httpServer?.start()
            } catch (e: IOException) {
                Log.e("BleService", "HTTP Server start failed", e)
            }
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
    }

    private fun restartHttpServer() {
        stopHttpServer()
        startHttpServer()
    }

    private fun startWebSocketServer() {
        if (webSocketServer == null) {
            val port = sharedPreferences.getInt("websocket_server_port", 8001)
            try {
                webSocketServer = WebSocketServerManager(port)
                webSocketServer?.start()
            } catch (e: Exception) {
                Log.e("BleService", "WebSocket Server start failed", e)
            }
        }
    }

    private fun stopWebSocketServer() {
        webSocketServer?.stopServer()
        webSocketServer = null
    }

    private fun restartWebSocketServer() {
        stopWebSocketServer()
        startWebSocketServer()
    }

    private inner class HttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession?): Response {
            if (session?.method == Method.GET && session.uri == "/heartrate") {
                val json = JSONObject().apply {
                    put("heart_rate", _heartRate.value)
                    put("connected", isDeviceConnected())
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopHttpServer()
        stopWebSocketServer()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}