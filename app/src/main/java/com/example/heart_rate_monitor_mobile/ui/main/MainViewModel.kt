package com.example.heart_rate_monitor_mobile.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.ble.BleStateTexts
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.github.mikephil.charting.data.Entry
import com.juul.kable.Advertisement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _uiEvents = MutableSharedFlow<MainUiEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiEvents: SharedFlow<MainUiEvent> = _uiEvents.asSharedFlow()

    // ---------- 实时图表 ----------

    private var chartStartTime = 0L
    // ArrayDeque：满员后头删 O(1)（mutableList.removeAt(0) 在 10000 点时是 O(n) 搬移）
    private val chartDataPoints = ArrayDeque<Entry>()

    /** 只读快照，避免把可变缓冲的引用暴露给 UI 层 */
    val chartHistory: List<Entry> get() = chartDataPoints.toList()

    private val _newChartEntry = MutableSharedFlow<Entry>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val newChartEntry: SharedFlow<Entry> = _newChartEntry.asSharedFlow()

    init {
        // 图表数据采集：收逐样本流（StateFlow 按值去重会让相同 BPM 不出点，时间密度失真）
        viewModelScope.launch {
            repository.heartRateSamples.collect { sample ->
                if (sample.bpm > 0 && appStatus.value == AppStatus.CONNECTED) {
                    addChartDataPoint(sample.bpm)
                }
            }
        }
        // 连接建立时重置图表；状态跃迁产生一次性提示
        viewModelScope.launch {
            var previous: BleState? = null
            repository.bleState.collect { state ->
                if (previous !is BleState.Connected && state is BleState.Connected) {
                    chartStartTime = System.currentTimeMillis()
                    chartDataPoints.clear()
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

    private fun addChartDataPoint(rate: Int) {
        val timeDiffSeconds = (System.currentTimeMillis() - chartStartTime) / 1000f
        val newEntry = Entry(timeDiffSeconds, rate.toFloat())
        if (chartDataPoints.size >= MAX_CHART_POINTS) {
            chartDataPoints.removeFirst()
        }
        chartDataPoints.add(newEntry)
        _newChartEntry.tryEmit(newEntry)
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

    private companion object {
        const val MAX_CHART_POINTS = 10000
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
