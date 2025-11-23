package com.example.heart_rate_monitor_mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteConstraintException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleManager
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.data.db.HeartRateRecord
import com.example.heart_rate_monitor_mobile.data.db.HeartRateSession
import com.example.heart_rate_monitor_mobile.service.server.HttpServerManager
import com.example.heart_rate_monitor_mobile.service.server.WebSocketServerManager
import com.example.heart_rate_monitor_mobile.ui.webhook.WebhookManager
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.peripheral
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
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
    private lateinit var db: AppDatabase
    private var locationManager: LocationManager? = null

    // --- Server Managers ---
    private var httpServerManager: HttpServerManager? = null
    private var webSocketServerManager: WebSocketServerManager? = null

    // --- StateFlow ---
    private val _bleState = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _scanResults = MutableStateFlow<List<Advertisement>>(emptyList())
    val scanResults: StateFlow<List<Advertisement>> = _scanResults.asStateFlow()

    private val webSocketStateFlow = MutableSharedFlow<String>(replay = 1)

    // --- BLE State ---
    private var connectedPeripheral: Peripheral? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    @Volatile private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)
    private var lastConnectedDeviceId: String? = null
    private var currentSessionId: Long? = null

    // --- Location Listener ---
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                _speed.value = location.speed * 3.6f
            } else {
                _speed.value = 0f
            }
            broadcastWebSocketState()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isDeviceConnected(): Boolean = connectedPeripheral?.state?.value is State.Connected

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(applicationContext)
        webhookManager = WebhookManager(applicationContext)
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        db = AppDatabase.getDatabase(applicationContext)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        startForegroundService()
        registerSettingsListener()

        updateHttpServerState()
        updateWebSocketServerState()
        updateLocationUpdates()
        broadcastWebSocketState()
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
            .setContentText("服务正在后台运行")
            .setSmallIcon(R.drawable.ic_bluetooth_connected)
            .setOngoing(true)
            .build()

        // 核心修复：防止崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

                // 只有当用户确实授予了位置权限时，才添加 LOCATION 类型
                val hasLocationPermission = ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val isSpeedEnabled = sharedPreferences.getBoolean("speed_display_enabled", false)

                if (hasLocationPermission && isSpeedEnabled) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                ServiceCompat.startForeground(this, 1, notification, type)
            } catch (e: Exception) {
                // 如果发生任何 SecurityException，降级启动
                try {
                    val safeType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    ServiceCompat.startForeground(this, 1, notification, safeType)
                } catch (e2: Exception) {
                    Log.e("BleService", "Failed to start service", e2)
                }
            }
        } else {
            startForeground(1, notification)
        }
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
                            when (state) {
                                is State.Connecting -> {
                                    if (_bleState.value !is BleState.AutoReconnecting) {
                                        _bleState.value = BleState.Connecting
                                    }
                                }
                                is State.Connected -> {
                                    val deviceName = peripheral.name ?: "未知设备"
                                    val msg = "已连接到 $deviceName"
                                    _bleState.value = BleState.Connected(msg)
                                    webhookManager.triggerWebhooks(WebhookTrigger.CONNECTED, speed = _speed.value)

                                    val isHistoryEnabled = sharedPreferences.getBoolean("history_recording_enabled", true)
                                    if (isHistoryEnabled) {
                                        val session = HeartRateSession(deviceName = deviceName, startTime = System.currentTimeMillis())
                                        currentSessionId = db.heartRateDao().insertSession(session)
                                    }

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
                    } catch (e: Exception) { }
                    cleanupConnection()
                    val autoReconnectEnabled = sharedPreferences.getBoolean("auto_reconnect_enabled", true)
                    if (autoReconnectEnabled && !isManuallyDisconnected && lastConnectedDeviceId != null) {
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
        currentSessionId?.let {
            serviceScope.launch {
                db.heartRateDao().endSession(it, System.currentTimeMillis())
                currentSessionId = null
            }
        }
        val message = if (isManuallyDisconnected) "已手动断开" else "设备连接已断开"
        if (_bleState.value !is State.Connected) {
            _bleState.value = BleState.Disconnected(message)
        }
        webhookManager.triggerWebhooks(WebhookTrigger.DISCONNECTED, _heartRate.value, _speed.value)
        _heartRate.value = 0
        broadcastWebSocketState()
        connectedPeripheral = null
    }

    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        try {
            val isHistoryEnabled = sharedPreferences.getBoolean("history_recording_enabled", true)
            bleManager.observeHeartRate(peripheral).collect { rate ->
                _heartRate.value = rate
                webhookManager.triggerWebhooks(WebhookTrigger.HEART_RATE_UPDATED, rate, _speed.value)
                if (isHistoryEnabled && currentSessionId != null) {
                    try {
                        val record = HeartRateRecord(sessionId = currentSessionId!!, timestamp = System.currentTimeMillis(), heartRate = rate)
                        db.heartRateDao().insertRecord(record)
                    } catch (e: SQLiteConstraintException) {
                        currentSessionId = null
                    }
                }
                broadcastWebSocketState()
            }
        } catch (e: Exception) { }
    }

    private fun broadcastWebSocketState() {
        val json = JSONObject().apply {
            put("heart_rate", _heartRate.value)
            put("connected", isDeviceConnected())
            put("status", _bleState.value.message)
            put("timestamp", System.currentTimeMillis())
            put("speed", _speed.value)
        }
        webSocketStateFlow.tryEmit(json.toString())
    }

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "http_server_enabled", "http_server_port" -> updateHttpServerState()
            "websocket_server_enabled", "websocket_server_port" -> updateWebSocketServerState()
            "speed_display_enabled" -> {
                updateLocationUpdates()
                startForegroundService()
            }
        }
    }

    private fun registerSettingsListener() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
    }

    private fun updateLocationUpdates() {
        val isEnabled = sharedPreferences.getBoolean("speed_display_enabled", false)
        if (isEnabled && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
            } catch (e: Exception) {
                Log.e("BleService", "Location update failed", e)
            }
        } else {
            locationManager?.removeUpdates(locationListener)
            _speed.value = 0f
            broadcastWebSocketState()
        }
    }

    private fun updateHttpServerState() {
        val isEnabled = sharedPreferences.getBoolean("http_server_enabled", false)
        if (isEnabled) {
            val port = sharedPreferences.getInt("http_server_port", 8000)
            if (httpServerManager == null) {
                httpServerManager?.stop()
                httpServerManager = HttpServerManager(port, _heartRate, _speed, ::isDeviceConnected) { _bleState.value.message }
                httpServerManager?.start()
            }
        } else {
            httpServerManager?.stop()
            httpServerManager = null
        }
    }

    private fun updateWebSocketServerState() {
        val isEnabled = sharedPreferences.getBoolean("websocket_server_enabled", false)
        if (isEnabled) {
            val port = sharedPreferences.getInt("websocket_server_port", 8001)
            if (webSocketServerManager == null) {
                webSocketServerManager?.stop()
                webSocketServerManager = WebSocketServerManager(port, webSocketStateFlow)
                webSocketServerManager?.start()
            }
        } else {
            webSocketServerManager?.stop()
            webSocketServerManager = null
        }
        broadcastWebSocketState()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        locationManager?.removeUpdates(locationListener)
        httpServerManager?.stop()
        webSocketServerManager?.stop()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}