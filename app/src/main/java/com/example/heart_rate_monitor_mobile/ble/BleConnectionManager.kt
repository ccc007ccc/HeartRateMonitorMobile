package com.example.heart_rate_monitor_mobile.ble

import android.util.Log
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun String.toUuid(): UUID = UUID.fromString(this)

/** 连接生命周期事件，供 domain 层驱动会话记录 / Webhook 等副作用 */
sealed interface BleEvent {
    data class Connected(val deviceId: String, val deviceName: String) : BleEvent

    /**
     * 逐通知的心率样本（不去重）。时序消费方（会话记录、预警状态机、图表）
     * 必须使用本事件流而非按值去重的 heartRate StateFlow。
     *
     * @param rrIntervalsMs 本帧携带的 RR 间期（毫秒），供 HRV 类分析使用；无则为空
     * @param timestampMs 样本墙钟时间（事件发射时打点，落库与展示共用）
     */
    data class HeartRateSample(
        val bpm: Int,
        val rrIntervalsMs: List<Int> = emptyList(),
        val timestampMs: Long = System.currentTimeMillis(),
    ) : BleEvent

    /** 仅在真正建立过连接后发射；[lastBpm] 为断开前最后一次心率（供 Webhook {bpm} 占位符） */
    data class Disconnected(val lastBpm: Int) : BleEvent
}

/** 单帧 Heart Rate Measurement (0x2A37) 解析结果 */
data class HeartRateMeasurement(val bpm: Int, val rrIntervalsMs: List<Int>)

/**
 * BLE 连接管理器：扫描、连接、心率订阅与自动重连的唯一实现。
 *
 * 状态与数据以 StateFlow 对外（进程级，替代旧的 Service 实例内 Flow + Binder 直连）。
 *
 * 并发模型——操作代次（epoch）：
 * 每个公共入口（扫描/定向连接/连接/断开）自增 [operationEpoch]。被新操作取消的旧协程
 * 在其 NonCancellable finally 中先校验自己的代次仍是当前值，才允许写状态、清引用、
 * 调度自动重连——否则只做自身资源释放（断开外设）与必要事件发射。
 * 这杜绝了"旧连接的清理逻辑覆盖新连接状态 / 幽灵重连循环"一类互踩问题。
 *
 * 自动重连策略（修复旧实现"只扫一次就放弃"的缺陷）：
 * - 意外断开且开关开启时，按 5s/10s/30s/60s 指数退避无限重试，60s 封顶；
 * - 连接成功或用户手动操作（扫描/连接/断开）时终止循环；
 * - 目标设备 ID 持久化（last_connected_device_id），服务重启后仍可恢复。
 */
class BleConnectionManager(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val scanner = Scanner()

    private val _bleState = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _scanResults = MutableStateFlow<List<Advertisement>>(emptyList())
    val scanResults: StateFlow<List<Advertisement>> = _scanResults.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    /** 操作代次：新的公共操作使旧协程的收尾写入全部失效 */
    private val operationEpoch = AtomicLong(0)

    private var connectedPeripheral: Peripheral? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)

    fun isDeviceConnected(): Boolean = connectedPeripheral?.state?.value is State.Connected

    private fun beginOperation(): Long = operationEpoch.incrementAndGet()

    private fun isCurrent(epoch: Long): Boolean = operationEpoch.get() == epoch

    /**
     * 手动扫描。
     * @return false 表示已有扫描在进行、本次请求被忽略（调用方可提示用户）
     */
    fun startScan(durationMillis: Long = SCAN_DURATION_MS): Boolean {
        if (!isScanning.compareAndSet(false, true)) return false
        val myEpoch = beginOperation()
        cancelReconnect()
        stopAllBleActivities()

        scanJob = scope.launch {
            val found = mutableMapOf<String, Advertisement>()
            try {
                _bleState.value = BleState.Scanning
                withTimeout(durationMillis) {
                    scanAdvertisements().collect { advertisement ->
                        found[advertisement.identifier] = advertisement
                        _scanResults.value = found.values.toList()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // 到时结束，正常路径
            } finally {
                withContext(NonCancellable) {
                    isScanning.set(false)
                    if (isCurrent(myEpoch)) {
                        _bleState.value = BleState.ScanFinished(foundAny = found.isNotEmpty())
                    }
                }
            }
        }
        return true
    }

    /**
     * 定向扫描目标设备并连接（应用启动自动连接与磁贴共用）。
     * @return false 表示已有扫描在进行、本次请求被忽略
     */
    fun startAutoConnectScan(deviceId: String, durationMillis: Long = SCAN_DURATION_MS): Boolean {
        if (!isScanning.compareAndSet(false, true)) return false
        val myEpoch = beginOperation()
        cancelReconnect()
        stopAllBleActivities()
        isManuallyDisconnected = false

        scanJob = scope.launch {
            _bleState.value = BleState.AutoConnecting
            targetScanAndConnect(deviceId, durationMillis, myEpoch, isReconnect = false)
        }
        return true
    }

    fun connectToDevice(identifier: String) {
        cancelReconnect()
        launchConnection(identifier)
    }

    fun disconnectDevice() {
        beginOperation()
        isManuallyDisconnected = true
        cancelReconnect()
        stopAllBleActivities()
        // 被取消的连接协程因代次失效不再写状态，这里直接落定手动断开的最终状态
        _heartRate.value = 0
        _bleState.value = BleState.Disconnected(BleState.DisconnectReason.MANUAL)
    }

    // ========== 内部实现 ==========

    private fun scanAdvertisements() = scanner.advertisements
        .catch { cause -> Log.e(TAG, "扫描过程中发生错误", cause) }

    /**
     * 在 [durationMillis] 内定向扫描 [deviceId]，扫到且代次仍有效时发起连接。
     * 调用方必须已持有 isScanning。
     * @return true 表示已发起连接
     */
    private suspend fun targetScanAndConnect(
        deviceId: String,
        durationMillis: Long,
        myEpoch: Long,
        isReconnect: Boolean,
    ): Boolean {
        var found = false
        var launched = false
        try {
            withTimeout(durationMillis) {
                scanAdvertisements().collect { advertisement ->
                    _scanResults.value = listOf(advertisement)
                    if (advertisement.identifier == deviceId) {
                        found = true
                        throw CancellationException("target found")
                    }
                }
            }
        } catch (e: CancellationException) {
            // "找到目标"的内部信号或外部取消，均交由 finally 按代次判定
            if (!found) Log.d(TAG, "定向扫描被取消: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "定向扫描失败", e)
        } finally {
            withContext(NonCancellable) {
                isScanning.set(false)
                if (isCurrent(myEpoch) && !isManuallyDisconnected) {
                    if (found) {
                        launchConnection(deviceId)
                        launched = true
                    } else if (!isReconnect && _bleState.value is BleState.AutoConnecting) {
                        _bleState.value = BleState.Disconnected(BleState.DisconnectReason.RECONNECT_NOT_FOUND)
                    }
                }
            }
        }
        return launched
    }

    private fun launchConnection(identifier: String) {
        val myEpoch = beginOperation()
        stopAllBleActivities()
        isManuallyDisconnected = false

        connectionJob = scope.launch {
            var peripheral: Peripheral? = null
            var wasConnected = false
            var lastBpm = 0
            try {
                peripheral = scope.peripheral(identifier)
                connectedPeripheral = peripheral
                settings.setAsync(SettingsKeys.LAST_CONNECTED_DEVICE_ID, identifier)

                if (_bleState.value !is BleState.AutoReconnecting) {
                    _bleState.value = BleState.Connecting
                }

                val stateMonitor = launch {
                    peripheral.state
                        // 只跳过连接建立前的初始 Disconnected；之后任何 Disconnected 都是真断开。
                        // 旧写法按 status != null 过滤，会吞掉对端体面断开（GATT status 0 → status=null），
                        // 导致手表主动断连时 App 永远停在"已连接"
                        .dropWhile { it is State.Disconnected }
                        .collect { state ->
                            Log.d(TAG, "外设状态: $state")
                            handlePeripheralState(peripheral, state, onConnected = { wasConnected = true }) {
                                lastBpm = it
                            }
                        }
                }

                withTimeout(CONNECT_TIMEOUT_MS) {
                    peripheral.connect()
                }
                stateMonitor.join()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "连接 $identifier 失败", e)
                }
                if (isCurrent(myEpoch) && !wasConnected && _bleState.value !is BleState.AutoReconnecting) {
                    _bleState.value = BleState.Disconnected(BleState.DisconnectReason.CONNECT_FAILED)
                }
            } finally {
                withContext(NonCancellable) {
                    cleanupConnection(peripheral, wasConnected, lastBpm, myEpoch)
                    if (isCurrent(myEpoch)) {
                        maybeScheduleReconnect()
                    }
                }
            }
        }
    }

    private fun CoroutineScope.handlePeripheralState(
        peripheral: Peripheral,
        state: State,
        onConnected: () -> Unit,
        onSample: (Int) -> Unit,
    ) {
        when (state) {
            is State.Connecting -> {
                if (_bleState.value !is BleState.AutoReconnecting) {
                    _bleState.value = BleState.Connecting
                }
            }
            is State.Connected -> {
                onConnected()
                val deviceName = peripheral.name ?: "未知设备"
                _bleState.value = BleState.Connected(deviceName)
                _events.tryEmit(BleEvent.Connected(peripheral.identifier, deviceName))
                launch { observeHeartRate(peripheral, onSample) }
            }
            is State.Disconnecting -> Unit
            is State.Disconnected -> {
                throw CancellationException("Device disconnected: ${state.status}")
            }
        }
    }

    private suspend fun observeHeartRate(peripheral: Peripheral, onSample: (Int) -> Unit) {
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID,
        )
        try {
            peripheral.observe(characteristic)
                .map { data ->
                    val measurement = parseHeartRateMeasurement(data)
                    Log.d(TAG, "HR frame: ${data.size}B bpm=${measurement.bpm} rr=${measurement.rrIntervalsMs.size}")
                    measurement
                }
                .collect { measurement ->
                    onSample(measurement.bpm)
                    _heartRate.value = measurement.bpm
                    _events.tryEmit(
                        BleEvent.HeartRateSample(measurement.bpm, measurement.rrIntervalsMs)
                    )
                }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.w(TAG, "心率数据流异常终止", e)
            }
        }
    }

    /**
     * 连接协程收尾。自身资源（外设断开、Disconnected 事件）无条件处理；
     * 共享状态（connectedPeripheral、_heartRate、_bleState）只在代次有效时写入，
     * 避免覆盖新操作的状态（旧缺陷：清空新连接的 peripheral 引用导致
     * isDeviceConnected() 永久 false）。
     */
    private suspend fun cleanupConnection(
        peripheral: Peripheral?,
        wasConnected: Boolean,
        lastBpm: Int,
        myEpoch: Long,
    ) {
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "断开外设时出错（可忽略）", e)
        }
        if (connectedPeripheral === peripheral) {
            connectedPeripheral = null
        }

        if (isCurrent(myEpoch)) {
            _heartRate.value = 0
            if (wasConnected || isManuallyDisconnected) {
                _bleState.value = BleState.Disconnected(
                    if (isManuallyDisconnected) BleState.DisconnectReason.MANUAL
                    else BleState.DisconnectReason.CONNECTION_LOST
                )
            }
        }

        // 只有真正建立过的连接才发 Disconnected（修复：连接失败/被取代也发事件
        // 导致伪 DISCONNECTED webhook 的行为回归）
        if (wasConnected) {
            _events.tryEmit(BleEvent.Disconnected(lastBpm))
        }
    }

    /**
     * 意外断开后的指数退避重连循环。
     * 循环持续到连接成功 / 用户新操作（代次失效）/ 自动重连开关关闭。
     */
    private fun maybeScheduleReconnect() {
        val current = settings.settings.value
        val targetId = current.connection.lastConnectedDeviceId ?: return
        if (!current.connection.autoReconnectEnabled || isManuallyDisconnected) return
        if (reconnectJob?.isActive == true) return

        val loopEpoch = operationEpoch.get()
        reconnectJob = scope.launch {
            var attempt = 0
            while (isCurrent(loopEpoch)) {
                val snapshot = settings.settings.value
                if (!snapshot.connection.autoReconnectEnabled || isManuallyDisconnected) break
                if (isDeviceConnected()) break

                delay(reconnectDelayMs(attempt))
                attempt++
                if (!isCurrent(loopEpoch)) break
                _bleState.value = BleState.AutoReconnecting(attempt)
                Log.i(TAG, "自动重连第 $attempt 次尝试")

                if (!isScanning.compareAndSet(false, true)) continue
                val launched = targetScanAndConnect(targetId, SCAN_DURATION_MS, loopEpoch, isReconnect = true)
                if (launched) break
                if (isCurrent(loopEpoch)) {
                    _bleState.value = BleState.Disconnected(BleState.DisconnectReason.RECONNECT_NOT_FOUND)
                }
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun stopAllBleActivities() {
        scanJob?.cancel()
        connectionJob?.cancel()
        _scanResults.value = emptyList()
    }

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val SCAN_DURATION_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

        /** 第 attempt 次（从 0 计）重连前的退避等待，60s 封顶（internal 供单元测试） */
        internal fun reconnectDelayMs(attempt: Int): Long =
            RECONNECT_DELAYS_MS[minOf(attempt, RECONNECT_DELAYS_MS.lastIndex)]

        /**
         * 解析 Heart Rate Measurement (0x2A37) 帧（internal 供单元测试）。
         *
         * flags 位：bit0=BPM 为 uint16；bit3=含 Energy Expended（2 字节，跳过）；
         * bit4=含 RR 间期（uint16 LE 序列，单位 1/1024 秒，换算为毫秒）。
         * 帧异常时返回 bpm=0（消费方均有 bpm<=0 守卫）。
         */
        internal fun parseHeartRateMeasurement(data: ByteArray): HeartRateMeasurement {
            if (data.isEmpty()) return HeartRateMeasurement(0, emptyList())
            val flags = data[0].toInt()
            var offset = 1
            val bpm: Int
            if ((flags and 0x01) != 0) {
                if (data.size < 3) return HeartRateMeasurement(0, emptyList())
                bpm = (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
                offset += 2
            } else {
                if (data.size < 2) return HeartRateMeasurement(0, emptyList())
                bpm = data[1].toInt() and 0xFF
                offset += 1
            }
            if ((flags and 0x08) != 0) offset += 2
            val rrIntervalsMs = if ((flags and 0x10) != 0) {
                buildList {
                    var i = offset
                    while (i + 1 < data.size) {
                        val raw = (data[i + 1].toInt() and 0xFF shl 8) or (data[i].toInt() and 0xFF)
                        add(raw * 1000 / 1024)
                        i += 2
                    }
                }
            } else {
                emptyList()
            }
            return HeartRateMeasurement(bpm, rrIntervalsMs)
        }
    }
}
