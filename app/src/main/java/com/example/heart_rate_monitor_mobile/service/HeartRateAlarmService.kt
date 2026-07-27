package com.example.heart_rate_monitor_mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.settings.AlarmSettings
import com.example.heart_rate_monitor_mobile.domain.AlarmStateMachine
import com.example.heart_rate_monitor_mobile.service.posture.PostureCalibration
import com.example.heart_rate_monitor_mobile.service.posture.PostureDetector
import com.example.heart_rate_monitor_mobile.service.posture.PostureType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 心率预警服务。
 *
 * specialUse 前台服务：实时心率来自 AppContainer.heartRate（不再 bindService），
 * 注册加速度传感器运行 PostureDetector，驱动 [AlarmStateMachine]（已抽为可单元测试的纯类）：
 * 仅静坐/站立姿态下，心率连续超过高限或低于低限达设定秒数时触发通知 + 震动报警。
 *
 * 冷启动经 HeartRateAlarmInitializer（ContentProvider）自动恢复。
 */
class HeartRateAlarmService : Service() {

    /** 当前姿态（预留扩展） */
    private val _posture = MutableStateFlow(PostureType.UNKNOWN)
    val posture = _posture.asStateFlow()

    private val container by lazy { AppContainer.get(this) }
    private lateinit var sensorManager: SensorManager
    private lateinit var postureDetector: PostureDetector
    private var isResidentForeground = false
    private var alarmMachine: AlarmStateMachine? = null

    private val classifyHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        postureDetector = PostureDetector()

        val alarm = container.settings.settings.value.alarm
        postureDetector.setCalibration(PostureCalibration.fromJson(alarm.postureCalibrationJson))
        alarmMachine = AlarmStateMachine(
            highThreshold = alarm.highThreshold,
            lowThreshold = alarm.lowThreshold,
            durationMs = alarm.durationSeconds.toLong() * 1000L,
            cooldownMs = alarm.effectiveCooldownMs(),
            onAlarm = ::triggerAlarm,
        )

        ensureResidentForeground()
        ensureBleServiceRunning()
        observeHeartRate()
        observeSettings()
        registerAccelerometer()
        classifyHandler.post(classifyRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureResidentForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        classifyHandler.removeCallbacks(classifyRunnable)
        sensorManager.unregisterListener(sensorListener)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isResidentForeground = false
    }

    /**
     * 预警依赖 BLE 链路。前台通道下确保 BleService 存活（后台启动被拒时降级）；
     * 无障碍通道下链路已由无障碍服务保活的进程承载，无需前台服务与其通知。
     */
    private fun ensureBleServiceRunning() {
        if (container.overlayCoordinator.isAccessibilityChannel()) return
        try {
            startService(Intent(this, BleService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "启动 BleService 被拒（后台限制），等待前台恢复", e)
        }
    }

    private fun observeHeartRate() {
        serviceScope.launch {
            // 必须收逐样本流而非按值去重的 StateFlow：心率稳定停在越界值时
            // StateFlow 不再发射，"持续 N 秒才报警"的判定会停摆
            container.heartRate.heartRateSamples.collect { sample ->
                // 蓝牙断开时心率为 0，忽略以避免误触发低限报警
                if (sample.bpm <= 0) return@collect
                alarmMachine?.onHeartRate(
                    sample.bpm,
                    postureDetector.currentStablePosture(),
                    now = sample.timestampMillis,
                )
            }
        }
    }

    /** 设置流驱动阈值与校准热更新（替代 OnSharedPreferenceChangeListener） */
    private fun observeSettings() {
        // 保活通道切换：无障碍生效即撤掉常驻通知，切回前台通道补回
        serviceScope.launch {
            container.overlayCoordinator.accessibilityActive.collect { ensureResidentForeground() }
        }
        serviceScope.launch {
            container.settings.flowOf { it.alarm }.collect { alarm ->
                alarmMachine?.updateThresholds(alarm.highThreshold, alarm.lowThreshold, alarm.durationSeconds)
                alarmMachine?.updateCooldown(alarm.effectiveCooldownMs())
                postureDetector.setCalibration(PostureCalibration.fromJson(alarm.postureCalibrationJson))
            }
        }
    }

    // ========== 加速度传感器 ==========

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            postureDetector.onSensorSample(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerAccelerometer() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** 每 200ms 分类一次姿态并更新 StateFlow */
    private val classifyRunnable = object : Runnable {
        override fun run() {
            _posture.value = postureDetector.classify()
            classifyHandler.postDelayed(this, CLASSIFY_INTERVAL_MS)
        }
    }

    // ========== 触发报警 ==========

    private fun triggerAlarm(rate: Int, isHigh: Boolean, posture: PostureType, threshold: Int) {
        val direction = getString(
            if (isHigh) R.string.alarm_direction_high else R.string.alarm_direction_low
        )
        val body = getString(
            R.string.alarm_body, rate, direction, threshold, getString(posture.labelRes)
        )
        showAlarmNotification(body)
        vibrate()
    }

    private fun showAlarmNotification(body: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            getString(R.string.alarm_title),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notif_channel_alarm_desc)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_heart)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
    }

    // ========== 前台保活 ==========

    /**
     * 以 specialUse 类型提升为前台服务，防止系统在锁屏/内存压力下杀死服务。
     * START_STICKY 重启且 app 在后台时 startForeground 可能被拒，捕获后降级为普通服务。
     */
    private fun ensureResidentForeground() {
        // 无障碍通道：进程由无障碍服务保活，不发常驻通知
        if (container.overlayCoordinator.isAccessibilityChannel()) {
            if (isResidentForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isResidentForeground = false
            }
            return
        }
        if (isResidentForeground) return
        try {
            val notification = createResidentNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isResidentForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "无法提升为前台服务，降级运行", e)
            isResidentForeground = false
        }
    }

    private fun createResidentNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            RESIDENT_CHANNEL_ID,
            getString(R.string.notif_channel_alarm_resident),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_alarm_resident_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, RESIDENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(getString(R.string.alarm_service_running))
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HeartRateAlarmService"
        private const val NOTIFICATION_ID = 0x5B02
        private const val ALARM_NOTIFICATION_ID = 0x5B03
        private const val RESIDENT_CHANNEL_ID = "heart_rate_alarm_resident"
        private const val ALARM_CHANNEL_ID = "heart_rate_alarm"
        private const val CLASSIFY_INTERVAL_MS = 200L
        private const val DEFAULT_COOLDOWN_MS = 60_000L
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 300, 500)

        /** 冷却时长：开启重复报警时用设定间隔，否则默认 60 秒 */
        fun AlarmSettings.effectiveCooldownMs(): Long =
            if (repeatEnabled) repeatIntervalMinutes * 60_000L else DEFAULT_COOLDOWN_MS
    }
}
