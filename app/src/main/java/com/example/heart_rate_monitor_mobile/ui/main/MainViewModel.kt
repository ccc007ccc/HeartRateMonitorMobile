package com.example.heart_rate_monitor_mobile.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.ble.BleStateTexts
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.domain.BpmDiffAccumulator
import com.example.heart_rate_monitor_mobile.domain.RollingRate
import com.juul.kable.Advertisement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class AppStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
}

/** 一次性 UI 事件（Toast 等），替代旧的 LiveData 误用 */
sealed interface MainUiEvent {
    data class ShowToast(val message: String) : MainUiEvent
}

/**
 * 主界面 ViewModel。
 *
 * 重构后不再持有任何 Service 引用（旧实现经 WeakReference<BleService> 委托，
 * Service 未绑定时所有操作静默失效）——数据与操作一律走进程级
 * [com.example.heart_rate_monitor_mobile.domain.HeartRateRepository]。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val repository = container.heartRate
    private val settings = container.settings

    // ---------- UI 状态 ----------

    val heartRate: StateFlow<Int> = repository.heartRate
    val speed: StateFlow<Float> = repository.speed

    val statusMessage: StateFlow<String> = repository.bleState
        .map { BleStateTexts.displayText(getApplication<Application>(), it) }
        .stateIn(
            viewModelScope, SharingStarted.Eagerly,
            BleStateTexts.displayText(application, repository.bleState.value),
        )

    val appStatus: StateFlow<AppStatus> = repository.bleState
        .map { it.toAppStatus() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.bleState.value.toAppStatus())

    /** 扫描结果：收藏设备置顶，其余按信号强度排序 */
    val scanResults: StateFlow<List<Advertisement>> = combine(
        repository.scanResults,
        settings.flowOf { it.connection.favoriteDeviceId },
    ) { results, favoriteId ->
        results.sortedWith(
            compareByDescending<Advertisement> { it.identifier == favoriteId }
                .thenByDescending { it.rssi }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- 设备评测指标（主设备 + 各对比设备：采样率 / Δ / MAE） ----------

    private val primaryRate = RollingRate()
    private val _sampleRate = MutableStateFlow(0f)

    /** 主设备实际上报频率（包/秒）；无数据或断开时为 0 */
    val sampleRate: StateFlow<Float> = _sampleRate.asStateFlow()

    /** 对比设备一行展示数据（colorIndex 与图表配色对齐：0 为主设备，1 起为对比设备） */
    data class ComparisonRow(
        val id: String,
        val name: String,
        val connected: Boolean,
        val bpm: Int,
        val rate: Float,
        val lastDiff: Int?,
        val meanAbsDiff: Float?,
        val colorIndex: Int,
    )

    private class ComparisonStats {
        val rate = RollingRate()
        val diff = BpmDiffAccumulator()
        var lastBpm = 0
    }

    private val comparisonStats = linkedMapOf<String, ComparisonStats>()
    private val _comparisonRows = MutableStateFlow<List<ComparisonRow>>(emptyList())
    val comparisonRows: StateFlow<List<ComparisonRow>> = _comparisonRows.asStateFlow()

    val comparisonScanResults: StateFlow<List<Advertisement>> get() = repository.comparison.scanResults
    val comparisonScanning: StateFlow<Boolean> get() = repository.comparison.isScanning

    /** 主设备名称（Connected 时非空），参赛列表首行展示用 */
    val primaryDeviceName: StateFlow<String> = repository.bleState
        .map { (it as? BleState.Connected)?.deviceName ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private fun publishComparisonRows(nowMs: Long) {
        val devices = repository.comparison.devices.value
        _comparisonRows.value = devices.values.mapIndexed { index, device ->
            val stats = comparisonStats.getOrPut(device.id) { ComparisonStats() }
            ComparisonRow(
                id = device.id,
                name = device.name,
                connected = device.connected,
                bpm = stats.lastBpm,
                rate = stats.rate.rateAt(nowMs),
                lastDiff = stats.diff.lastDiff,
                meanAbsDiff = stats.diff.meanAbsDiff,
                colorIndex = index + 1,
            )
        }
    }

    private val _uiEvents = MutableSharedFlow<MainUiEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiEvents: SharedFlow<MainUiEvent> = _uiEvents.asSharedFlow()

    // ---------- 实时图表（多序列：主设备 + 各对比设备） ----------

    /** 图表点（seriesId, 墙钟毫秒, bpm） */
    data class ChartPoint(val seriesId: String, val timestampMs: Long, val bpm: Int)

    // ArrayDeque：满员后头删 O(1)
    private val chartBuffers = linkedMapOf<String, ArrayDeque<ChartPoint>>()

    /** 各序列的只读快照（key 顺序：primary 在前，随后为对比设备加入顺序） */
    fun chartSnapshot(): Map<String, List<ChartPoint>> =
        chartBuffers.mapValues { it.value.toList() }

    private val _newChartPoint = MutableSharedFlow<ChartPoint>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val newChartPoint: SharedFlow<ChartPoint> = _newChartPoint.asSharedFlow()

    /** 序列结构变化（对比设备增减/主设备重连清空）→ UI 需整体重建图表 */
    private val _chartStructureChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val chartStructureChanged: SharedFlow<Unit> = _chartStructureChanged.asSharedFlow()

    private fun bufferChartPoint(point: ChartPoint) {
        val buffer = chartBuffers.getOrPut(point.seriesId) { ArrayDeque() }
        if (buffer.size >= MAX_CHART_POINTS) buffer.removeFirst()
        buffer.addLast(point)
        _newChartPoint.tryEmit(point)
    }

    init {
        // 主设备样本：图表 + 采样率（逐样本流，StateFlow 去重会让相同 BPM 不出点）
        viewModelScope.launch {
            repository.heartRateSamples.collect { sample ->
                _sampleRate.value = primaryRate.onSample(sample.timestampMillis)
                if (sample.bpm > 0 && appStatus.value == AppStatus.CONNECTED) {
                    bufferChartPoint(ChartPoint(PRIMARY_SERIES_ID, sample.timestampMillis, sample.bpm))
                }
            }
        }
        // 对比设备样本：指标（速率/Δ/MAE）+ 图表序列
        viewModelScope.launch {
            repository.comparison.samples.collect { tagged ->
                val stats = comparisonStats.getOrPut(tagged.deviceId) { ComparisonStats() }
                stats.lastBpm = tagged.sample.bpm
                stats.rate.onSample(tagged.sample.timestampMillis)
                stats.diff.onSample(tagged.sample.bpm, repository.heartRate.value)
                if (tagged.sample.bpm > 0) {
                    bufferChartPoint(ChartPoint(tagged.deviceId, tagged.sample.timestampMillis, tagged.sample.bpm))
                }
                publishComparisonRows(tagged.sample.timestampMillis)
            }
        }
        // 对比设备增减：同步指标表 + 触发图表结构重建
        viewModelScope.launch {
            repository.comparison.devices.collect { devices ->
                comparisonStats.keys.retainAll(devices.keys)
                chartBuffers.keys.retainAll(devices.keys + PRIMARY_SERIES_ID)
                publishComparisonRows(System.currentTimeMillis())
                _chartStructureChanged.tryEmit(Unit)
            }
        }
        // 速率衰减：数据停止上报时逐渐归零
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                _sampleRate.value = primaryRate.rateAt(now)
                if (comparisonStats.isNotEmpty()) publishComparisonRows(now)
            }
        }
        // 连接建立时重置图表与评测指标（新会话）；状态跃迁产生一次性提示
        viewModelScope.launch {
            var previous: BleState? = null
            repository.bleState.collect { state ->
                if (previous !is BleState.Connected && state is BleState.Connected) {
                    chartBuffers.values.forEach { it.clear() }
                    comparisonStats.values.forEach { it.diff.reset() }
                    _chartStructureChanged.tryEmit(Unit)
                }
                when {
                    state is BleState.Connected ->
                        _uiEvents.tryEmit(
                            MainUiEvent.ShowToast(
                                getApplication<Application>().getString(R.string.common_connected)
                            )
                        )
                    state is BleState.AutoReconnecting && state.attempt == 1 ->
                        _uiEvents.tryEmit(
                            MainUiEvent.ShowToast(
                                getApplication<Application>().getString(R.string.main_toast_reconnecting)
                            )
                        )
                    else -> Unit
                }
                previous = state
            }
        }
    }

    // ---------- 操作 ----------

    fun startScan() {
        if (!repository.startScan()) {
            _uiEvents.tryEmit(
                MainUiEvent.ShowToast(
                    getApplication<Application>().getString(R.string.main_toast_scan_in_progress)
                )
            )
        }
    }

    fun connectToDevice(identifier: String) = repository.connectToDevice(identifier)

    fun disconnectDevice() = repository.disconnectDevice()

    /** 应用启动时按设置自动连接收藏设备 */
    fun autoConnectIfEnabled() {
        val connection = settings.settings.value.connection
        if (connection.autoConnectEnabled && connection.favoriteDeviceId != null) {
            repository.autoConnect()
        }
    }

    fun onLocationPermissionGranted() = repository.refreshSpeedMonitor()

    // ---------- 对比设备操作 ----------

    fun startComparisonScan() = repository.comparison.startScan()

    fun connectComparisonDevice(ad: Advertisement) =
        repository.comparison.connect(ad.identifier, ad.name)

    fun removeComparisonDevice(id: String) = repository.comparison.remove(id)

    /** 断开的对比设备行点击重连 */
    fun reconnectComparisonDevice(id: String, name: String) {
        repository.comparison.remove(id)
        repository.comparison.connect(id, name)
    }

    // ---------- 收藏设备 ----------

    fun isDeviceFavorite(identifier: String): Boolean =
        settings.settings.value.connection.favoriteDeviceId == identifier

    fun toggleFavoriteDevice(ad: Advertisement) {
        val id = ad.identifier
        val currentFavorite = settings.settings.value.connection.favoriteDeviceId
        val newFavorite = if (currentFavorite == id) null else id
        viewModelScope.launch {
            if (newFavorite != null) {
                settings.set(SettingsKeys.FAVORITE_DEVICE_ID, newFavorite)
                addToFavoriteHistory(id, ad.name ?: "未知设备")
            } else {
                settings.remove(SettingsKeys.FAVORITE_DEVICE_ID)
            }
        }
    }

    /**
     * 将设备添加到收藏历史列表（JSON 数组）。
     * 去重（同 ID 旧记录移除）、新记录插入头部、最多保留 20 条。
     */
    private suspend fun addToFavoriteHistory(id: String, name: String) {
        val json = settings.settings.value.connection.favoriteDeviceHistoryJson
        try {
            val oldArr = JSONArray(json)
            val newArr = JSONArray().put(
                JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("timestamp", System.currentTimeMillis())
                }
            )
            for (i in 0 until oldArr.length()) {
                val obj = oldArr.getJSONObject(i)
                if (obj.getString("id") != id && newArr.length() < MAX_FAVORITE_HISTORY) {
                    newArr.put(obj)
                }
            }
            settings.set(SettingsKeys.FAVORITE_DEVICE_HISTORY, newArr.toString())
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "更新收藏历史失败", e)
        }
    }

    companion object {
        /** 主设备在图表/配色中的序列 ID（colorIndex 0） */
        const val PRIMARY_SERIES_ID = "primary"
        private const val MAX_CHART_POINTS = 10000
        const val MAX_FAVORITE_HISTORY = 20

        fun BleState.toAppStatus(): AppStatus = when (this) {
            is BleState.Scanning -> AppStatus.SCANNING
            is BleState.AutoConnecting, is BleState.Connecting, is BleState.AutoReconnecting ->
                AppStatus.CONNECTING
            is BleState.Connected -> AppStatus.CONNECTED
            else -> AppStatus.DISCONNECTED
        }
    }
}
