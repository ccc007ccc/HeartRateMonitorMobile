package com.example.heart_rate_monitor_mobile.domain

import com.example.heart_rate_monitor_mobile.ble.BleConnectionManager
import com.example.heart_rate_monitor_mobile.ble.BleEvent
import com.example.heart_rate_monitor_mobile.ble.ComparisonDeviceManager
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import com.example.heart_rate_monitor_mobile.data.db.SessionRecorder
import com.example.heart_rate_monitor_mobile.data.location.SpeedMonitor
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import com.example.heart_rate_monitor_mobile.data.webhook.WebhookRepository
import com.juul.kable.Advertisement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一次心率测量样本（逐样本、带时间戳——与 Polar/Health Services 等业界模型一致）。
 * [rrIntervalsMs] 为该帧携带的 RR 间期（毫秒），供 HRV 类分析使用，无则为空。
 */
data class HrSample(
    val bpm: Int,
    val timestampMillis: Long,
    val rrIntervalsMs: List<Int> = emptyList(),
)

/**
 * 心率数据的进程级单一事实来源（domain 层）。
 *
 * 所有消费方（MainViewModel、悬浮窗/状态栏/预警服务、QS 磁贴、内置服务器）
 * 都从这里取数与发起操作——彻底替代旧的 bindService + Binder + WeakReference 直连。
 *
 * 同时承担副作用编排：连接事件 → 会话记录 / Webhook 触发。
 */
class HeartRateRepository(
    scope: CoroutineScope,
    private val ble: BleConnectionManager,
    private val speedMonitor: SpeedMonitor,
    private val sessionRecorder: SessionRecorder,
    private val webhooks: WebhookRepository,
    private val settings: SettingsRepository,
    /** 多设备对比评测的连接层（纯仪表语义：无 Webhook/历史副作用） */
    val comparison: ComparisonDeviceManager,
) {
    val bleState: StateFlow<BleState> = ble.bleState

    /** 最新心率（StateFlow，按值去重）：适合"当前值展示"类 UI（数字、悬浮窗、通知） */
    val heartRate: StateFlow<Int> = ble.heartRate

    /**
     * 逐样本心率流（不去重、带时间戳）：预警判定、图表等**时序敏感**的消费方必须用这个——
     * StateFlow 在数值稳定不变时不发射，会让"越界持续 N 秒"这类判定停摆。
     */
    private val _heartRateSamples = MutableSharedFlow<HrSample>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val heartRateSamples: SharedFlow<HrSample> = _heartRateSamples.asSharedFlow()

    val scanResults: StateFlow<List<Advertisement>> = ble.scanResults
    val speed: StateFlow<Float> = speedMonitor.speed

    /** 对比设备最近读数（供 HTTP/WS 接口的 devices 数组，纯增量字段） */
    data class ComparisonReading(val name: String, val bpm: Int, val timestampMs: Long)

    private val _comparisonReadings = MutableStateFlow<Map<String, ComparisonReading>>(emptyMap())
    val comparisonReadings: StateFlow<Map<String, ComparisonReading>> = _comparisonReadings.asStateFlow()

    private var primaryDeviceId: String = ""
    private var primaryDeviceName: String = ""

    init {
        observeComparisonSamples(scope)
        scope.launch {
            ble.events.collect { event ->
                when (event) {
                    is BleEvent.Connected -> {
                        primaryDeviceId = event.deviceId
                        primaryDeviceName = event.deviceName
                        sessionRecorder.onPrimaryConnected(event.deviceId, event.deviceName)
                        webhooks.triggerWebhooks(WebhookTrigger.CONNECTED, speed = speed.value)
                    }
                    is BleEvent.HeartRateSample -> {
                        // 时间戳来自 BLE 层发射点，避免经 SharedFlow 转发后二次打点产生偏移
                        _heartRateSamples.tryEmit(
                            HrSample(event.bpm, event.timestampMs, event.rrIntervalsMs)
                        )
                        sessionRecorder.onSample(
                            deviceKey = primaryDeviceId,
                            deviceName = primaryDeviceName,
                            isPrimary = true,
                            bpm = event.bpm,
                            timestampMs = event.timestampMs,
                            rrIntervalsMs = event.rrIntervalsMs,
                        )
                        webhooks.triggerWebhooks(WebhookTrigger.HEART_RATE_UPDATED, event.bpm, speed.value)
                    }
                    is BleEvent.Disconnected -> {
                        sessionRecorder.onPrimaryDisconnected()
                        // lastBpm 来自事件载荷：断开时 heartRate StateFlow 可能已被清零
                        webhooks.triggerWebhooks(WebhookTrigger.DISCONNECTED, event.lastBpm, speed.value)
                    }
                }
            }
        }
    }

    fun isDeviceConnected(): Boolean = ble.isDeviceConnected()

    private fun observeComparisonSamples(scope: CoroutineScope) {
        scope.launch {
            comparison.samples.collect { tagged ->
                val name = comparison.devices.value[tagged.deviceId]?.name ?: tagged.deviceId
                _comparisonReadings.value = _comparisonReadings.value +
                    (tagged.deviceId to ComparisonReading(name, tagged.sample.bpm, tagged.sample.timestampMillis))
                // 会话记录：对比设备样本同轨入库（会话由主设备生死决定）
                sessionRecorder.onSample(
                    deviceKey = tagged.deviceId,
                    deviceName = name,
                    isPrimary = false,
                    bpm = tagged.sample.bpm,
                    timestampMs = tagged.sample.timestampMillis,
                    rrIntervalsMs = tagged.sample.rrIntervalsMs,
                )
            }
        }
        scope.launch {
            comparison.devices.collect { devices ->
                _comparisonReadings.value = _comparisonReadings.value.filterKeys { it in devices }
            }
        }
    }

    /** @return false 表示扫描请求被忽略（已有扫描进行中） */
    fun startScan(): Boolean = ble.startScan()

    fun connectToDevice(identifier: String) = ble.connectToDevice(identifier)

    fun disconnectDevice() = ble.disconnectDevice()

    /**
     * 自动连接：优先收藏设备，其次最近一次连接的设备。
     * 供应用启动自动连接与 QS 磁贴点按使用。
     * @return 是否发起了连接尝试
     */
    fun autoConnect(): Boolean {
        if (isDeviceConnected()) return true
        val connection = settings.settings.value.connection
        val targetId = connection.favoriteDeviceId ?: connection.lastConnectedDeviceId ?: return false
        return ble.startAutoConnectScan(targetId)
    }

    /** 权限授予后刷新 GPS 速度采集 */
    fun refreshSpeedMonitor() = speedMonitor.refreshAsync()
}
