package com.example.heart_rate_monitor_mobile.ui.main

import android.app.Application
import androidx.lifecycle.*
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.service.BleService
import com.github.mikephil.charting.data.Entry
import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class AppStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("app_settings", Application.MODE_PRIVATE)
    private var bleService: BleService? = null

    // --- LiveData for UI ---
    private val _statusMessage = MutableLiveData("Click button below to scan")
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _appStatus = MutableLiveData(AppStatus.DISCONNECTED)
    val appStatus: LiveData<AppStatus> get() = _appStatus

    // --- Chart State Management ---
    private var chartStartTime = 0L
    private val chartDataPoints = mutableListOf<Entry>()
    private val _newChartEntry = MutableLiveData<Entry>()
    val newChartEntry: LiveData<Entry> get() = _newChartEntry

    val chartHistory: List<Entry> get() = chartDataPoints

    // --- Service Data Flows ---
    lateinit var heartRate: LiveData<Int>
    lateinit var scanResults: LiveData<List<Advertisement>>

    fun setBleService(service: BleService) {
        this.bleService = service
        initializeDataStreams(service)
    }

    private fun initializeDataStreams(service: BleService) {
        // Expose LiveData for UI text and animations
        heartRate = service.heartRate.asLiveData()
        scanResults = service.scanResults.asLiveData()

        // Observe the BLE connection state
        viewModelScope.launch {
            service.bleState.collect { state ->
                _statusMessage.value = state.message
                val newStatus = when (state) {
                    is BleState.Scanning -> AppStatus.SCANNING
                    is BleState.AutoConnecting, is BleState.Connecting, is BleState.AutoReconnecting -> AppStatus.CONNECTING
                    is BleState.Connected -> AppStatus.CONNECTED
                    else -> AppStatus.DISCONNECTED
                }

                if (_appStatus.value != AppStatus.CONNECTED && newStatus == AppStatus.CONNECTED) {
                    initializeChart()
                }

                _appStatus.value = newStatus
            }
        }

        // 【核心修改】直接在ViewModel中收集心率数据以更新图表点
        // 这个协程在ViewModel存在期间会一直运行，即使UI在后台
        viewModelScope.launch {
            service.heartRate.collect { rate ->
                if (rate > 0 && _appStatus.value == AppStatus.CONNECTED) {
                    addChartDataPoint(rate)
                }
            }
        }
    }

    private fun initializeChart() {
        chartStartTime = System.currentTimeMillis()
        chartDataPoints.clear()
    }

    private fun addChartDataPoint(rate: Int) {
        if (appStatus.value != AppStatus.CONNECTED) return

        val timeDiffSeconds = (System.currentTimeMillis() - chartStartTime) / 1000f
        val newEntry = Entry(timeDiffSeconds, rate.toFloat())

        chartDataPoints.add(newEntry)
        _newChartEntry.value = newEntry // 只通知UI有“一个”新点
    }

    // --- Actions delegated to the service ---
    fun startScan() {
        bleService?.startScan()
    }

    fun startAutoConnectScan(identifier: String) {
        bleService?.startAutoConnectScan(identifier)
    }

    fun connectToDevice(identifier: String) {
        bleService?.connectToDevice(identifier)
    }

    fun disconnectDevice() {
        bleService?.disconnectDevice()
    }

    // --- Favorite device logic ---
    fun isDeviceFavorite(identifier: String): Boolean {
        return sharedPrefs.getString("favorite_device_id", null) == identifier
    }

    fun toggleFavoriteDevice(ad: Advertisement) {
        val id = ad.identifier
        val currentFavorite = sharedPrefs.getString("favorite_device_id", null)
        val newFavorite = if (currentFavorite == id) null else id
        with(sharedPrefs.edit()) {
            putString("favorite_device_id", newFavorite)
            apply()
        }
    }
}