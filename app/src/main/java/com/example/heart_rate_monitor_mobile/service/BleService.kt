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
    @Volatile private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)

    // Public method to check connection status
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
    }

    private fun startForegroundService() {
        val channelId = "BleServiceChannel"
        val channelName = "BLE Connection Status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Heart Rate Monitor")
            .setContentText("Service is running to maintain BLE connection.")
            .setSmallIcon(R.drawable.ic_bluetooth_connected) // Use an appropriate icon
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
                        // Add to a temporary set to handle duplicates from the flow
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

    /**
     * 【新增方法】启动一个特殊的扫描，用于自动连接收藏的设备。
     * 这个扫描会更新UI列表，并在找到目标设备后自动连接。
     * @param favoriteDeviceId 要自动连接的设备的ID
     * @param durationMillis 扫描的持续时间（超时）
     */
    fun startAutoConnectScan(favoriteDeviceId: String, durationMillis: Long = 15_000L) {
        if (!isScanning.compareAndSet(false, true)) return
        stopAllBleActivities()

        scanJob = serviceScope.launch {
            val foundDevices = mutableSetOf<Advertisement>()
            var favoriteFound = false
            _bleState.value = BleState.AutoConnecting

            try {
                withTimeout(durationMillis) {
                    bleManager.scan().collect { advertisement ->
                        if (foundDevices.none { it.identifier == advertisement.identifier }) {
                            foundDevices.add(advertisement)
                            _scanResults.value = foundDevices.toList()
                        }

                        if (advertisement.identifier == favoriteDeviceId) {
                            favoriteFound = true
                            this.coroutineContext.job.cancel() // 找到设备，停止扫描
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略超时或取消异常，这是停止扫描的预期方式
            } finally {
                withContext(NonCancellable) {
                    isScanning.set(false)
                    if (favoriteFound) {
                        // 如果找到了收藏的设备，则发起连接
                        connectToDevice(favoriteDeviceId)
                    } else {
                        // 如果超时仍未找到
                        if (_bleState.value is BleState.AutoConnecting) {
                            _bleState.value = BleState.ScanFailed("自动连接失败: 未找到收藏的设备")
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
                _bleState.value = BleState.Connecting

                val stateMonitor = launch {
                    peripheral.state
                        .filter { it !is State.Disconnected || it.status != null }
                        .collect { state ->
                            Log.d("BleService", "Filtered Connection State Changed: $state")
                            when (state) {
                                is State.Connecting -> _bleState.value = BleState.Connecting
                                is State.Connected -> {
                                    val msg = "已连接到 ${peripheral.name ?: "未知设备"}"
                                    _bleState.value = BleState.Connected(msg)
                                    webhookManager.triggerWebhooks(WebhookTrigger.CONNECTED)
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
                _bleState.value = BleState.Disconnected(errorMessage)
            } finally {
                withContext(NonCancellable) {
                    try {
                        peripheral?.disconnect()
                    } catch (e: Exception) {
                        Log.w("BleService", "Error during disconnect in finally block", e)
                    }
                    cleanupConnection()
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
        _bleState.value = BleState.Disconnected(message)
        webhookManager.triggerWebhooks(WebhookTrigger.DISCONNECTED)

        connectedPeripheral = null
        _heartRate.value = 0
    }

    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        try {
            bleManager.observeHeartRate(peripheral).collect { rate ->
                if (rate != _heartRate.value) {
                    _heartRate.value = rate
                    webhookManager.triggerWebhooks(WebhookTrigger.HEART_RATE_UPDATED, rate)
                }
            }
        } catch (e: Exception) {
            Log.w("BleService", "Heart rate observation stopped or failed.", e)
        }
    }

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "http_server_enabled" -> if (prefs.getBoolean(key, false)) startHttpServer() else stopHttpServer()
            "http_server_port" -> if (prefs.getBoolean("http_server_enabled", false)) restartHttpServer()
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
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}