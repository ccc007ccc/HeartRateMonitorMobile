package com.example.heart_rate_monitor_mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.ble.BleStateTexts
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * BLE 前台保活壳。
 *
 * 重构后本服务不再承载任何业务逻辑（BLE 连接 / 数据库 / GPS / 服务器 / Webhook
 * 全部下沉到 AppContainer 中的 repository 层），只负责：
 * 1. 前台服务保活（进程存活 = 心率链路存活）；
 * 2. 通知栏实时展示连接状态与心率；
 * 3. 随生命周期启停 ServerController（服务器只在心率服务运行期间可用）；
 * 4. 响应 QS 磁贴 / 启动器的自动连接指令。
 */
class BleService : Service() {

    private val container by lazy { AppContainer.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        container.serverController.start()
        container.heartRate.refreshSpeedMonitor()
        observeForNotification()
        observeSpeedSettingForServiceType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        startForegroundService()
        container.heartRate.refreshSpeedMonitor()
        if (intent?.action == ACTION_AUTO_CONNECT) {
            container.heartRate.autoConnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        container.serverController.stop()
    }

    // ========== 前台通知 ==========

    /** 通知栏实时刷新连接状态与心率（2 秒采样避免每次心跳都重建通知） */
    @OptIn(FlowPreview::class)
    private fun observeForNotification() {
        serviceScope.launch {
            combine(container.heartRate.bleState, container.heartRate.heartRate) { state, rate ->
                state to rate
            }
                .sample(NOTIFICATION_UPDATE_INTERVAL_MS)
                .distinctUntilChanged()
                .collect { (state, rate) -> updateNotification(state, rate) }
        }
    }

    /** 速度显示开关变化时需要增删 location 前台服务类型 */
    private fun observeSpeedSettingForServiceType() {
        serviceScope.launch {
            container.settings.flowOf { it.general.speedDisplayEnabled }
                .collect { startForegroundService() }
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_ble), NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_bluetooth_connected)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun notificationText(state: BleState, rate: Int): String =
        if (state is BleState.Connected && rate > 0) {
            "${state.deviceName} · $rate BPM"
        } else {
            BleStateTexts.displayText(this, state)
        }

    private fun updateNotification(state: BleState, rate: Int) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(notificationText(state, rate)))
    }

    private fun startForegroundService() {
        val notification = buildNotification(
            notificationText(container.heartRate.bleState.value, container.heartRate.heartRate.value)
        )

        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocationPermission = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val isSpeedEnabled = container.settings.settings.value.general.speedDisplayEnabled

            if (hasLocationPermission && isSpeedEnabled) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }

            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "无法启动带 Location 类型的前台服务，尝试降级启动", e)
                try {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "致命错误：无法启动前台服务", e2)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_AUTO_CONNECT = "com.example.heart_rate_monitor_mobile.AUTO_CONNECT"
        private const val TAG = "BleService"
        private const val CHANNEL_ID = "BleServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2000L
    }
}
