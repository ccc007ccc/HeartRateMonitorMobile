package com.example.heart_rate_monitor_mobile.ble

import android.util.Log
import com.example.heart_rate_monitor_mobile.domain.HrSample
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 一台对比设备的连接快照 */
data class ComparisonDevice(
    val id: String,
    val name: String,
    val connected: Boolean,
)

/** 带设备标签的心率样本（多设备对比图/指标的数据源） */
data class TaggedSample(val deviceId: String, val sample: HrSample)

/**
 * 对比设备管理器（多设备评测工具的连接层）。
 *
 * 与主连接引擎 [BleConnectionManager] 完全独立：
 * - 各对比设备独立连接、独立心率订阅，互不影响，也不影响主设备；
 * - 纯"仪表"语义：不触发 Webhook、不写历史会话、断开不自动重连（评测工具手动管理）；
 * - 扫描使用独立 Scanner，**不会**像主引擎那样取消进行中的连接——
 *   主设备已连接时也能随时扫描并添加对比设备。
 */
class ComparisonDeviceManager(private val scope: CoroutineScope) {

    private val scanner = Scanner()
    private var scanJob: Job? = null

    private val _scanResults = MutableStateFlow<List<Advertisement>>(emptyList())
    val scanResults: StateFlow<List<Advertisement>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, ComparisonDevice>>(emptyMap())
    val devices: StateFlow<Map<String, ComparisonDevice>> = _devices.asStateFlow()

    private val _samples = MutableSharedFlow<TaggedSample>(
        extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: SharedFlow<TaggedSample> = _samples.asSharedFlow()

    private val connectionJobs = mutableMapOf<String, Job>()

    /** 独立扫描（不干扰任何已有连接）。重复调用会重启扫描窗口 */
    fun startScan(durationMillis: Long = SCAN_DURATION_MS) {
        scanJob?.cancel()
        scanJob = scope.launch {
            val found = mutableMapOf<String, Advertisement>()
            _isScanning.value = true
            try {
                withTimeout(durationMillis) {
                    scanner.advertisements
                        .catch { cause -> Log.e(TAG, "对比设备扫描错误", cause) }
                        .collect { advertisement ->
                            found[advertisement.identifier] = advertisement
                            _scanResults.value = found.values.toList()
                        }
                }
            } catch (_: TimeoutCancellationException) {
                // 到时结束
            } finally {
                withContext(NonCancellable) { _isScanning.value = false }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    /** 连接一台对比设备（已在列表中则忽略） */
    fun connect(identifier: String, advertisedName: String?) {
        if (connectionJobs.containsKey(identifier)) return
        val initialName = advertisedName ?: identifier
        _devices.update { it + (identifier to ComparisonDevice(identifier, initialName, connected = false)) }

        connectionJobs[identifier] = scope.launch {
            var peripheral: Peripheral? = null
            try {
                peripheral = scope.peripheral(identifier)

                val stateJob = launch {
                    peripheral.state
                        .dropWhileInitialDisconnected()
                        .collect { state ->
                            when (state) {
                                is State.Connected -> {
                                    val name = peripheral.name ?: initialName
                                    _devices.update {
                                        it + (identifier to ComparisonDevice(identifier, name, connected = true))
                                    }
                                    launch { observeHeartRate(identifier, peripheral) }
                                }
                                is State.Disconnected ->
                                    throw CancellationException("comparison device disconnected")
                                else -> Unit
                            }
                        }
                }

                withTimeout(CONNECT_TIMEOUT_MS) { peripheral.connect() }
                stateJob.join()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "对比设备 $identifier 连接失败", e)
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        peripheral?.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG, "断开对比设备出错（可忽略）", e)
                    }
                    // 断开后标记状态但保留在列表中（UI 可展示"已断开"并允许移除/重连）
                    _devices.update { map ->
                        map[identifier]?.let { map + (identifier to it.copy(connected = false)) } ?: map
                    }
                    connectionJobs.remove(identifier)
                }
            }
        }
    }

    /** 断开并从列表移除一台对比设备 */
    fun remove(identifier: String) {
        connectionJobs.remove(identifier)?.cancel()
        _devices.update { it - identifier }
    }

    fun removeAll() {
        connectionJobs.keys.toList().forEach { remove(it) }
    }

    private suspend fun observeHeartRate(deviceId: String, peripheral: Peripheral) {
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID,
        )
        try {
            peripheral.observe(characteristic)
                .map { data -> BleConnectionManager.parseHeartRateMeasurement(data) }
                .collect { measurement ->
                    _samples.tryEmit(
                        TaggedSample(
                            deviceId,
                            HrSample(measurement.bpm, System.currentTimeMillis(), measurement.rrIntervalsMs),
                        )
                    )
                }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.w(TAG, "对比设备 $deviceId 心率流终止", e)
            }
        }
    }

    private fun kotlinx.coroutines.flow.Flow<State>.dropWhileInitialDisconnected() =
        kotlinx.coroutines.flow.flow {
            var passedInitial = false
            collect { state ->
                if (!passedInitial && state is State.Disconnected) return@collect
                passedInitial = true
                emit(state)
            }
        }

    private companion object {
        const val TAG = "ComparisonDeviceMgr"
        const val SCAN_DURATION_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
