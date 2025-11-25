package com.example.heart_rate_monitor_mobile.ui.main

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.*
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.service.BleService
import com.github.mikephil.charting.data.Entry
import com.juul.kable.Advertisement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

enum class AppStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("app_settings", Application.MODE_PRIVATE)

    private var bleServiceRef: WeakReference<BleService>? = null

    private var serviceDataJob: Job? = null

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

    private val MAX_CHART_POINTS = 10000

    val chartHistory: List<Entry> get() = chartDataPoints

    // --- Service Data Flows ---
    private val _heartRate = MutableLiveData<Int>()
    val heartRate: LiveData<Int> get() = _heartRate

    private val _speed = MutableLiveData<Float>()
    val speed: LiveData<Float> get() = _speed

    private val _scanResults = MutableLiveData<List<Advertisement>>()
    val scanResults: LiveData<List<Advertisement>> get() = _scanResults

    fun setBleService(service: BleService) {
        if (bleServiceRef?.get() === service && serviceDataJob?.isActive == true) return

        this.bleServiceRef = WeakReference(service)
        initializeDataStreams(service)
    }

    private fun initializeDataStreams(service: BleService) {
        serviceDataJob?.cancel()

        serviceDataJob = viewModelScope.launch {
            launch {
                service.heartRate.collect { rate ->
                    _heartRate.postValue(rate)
                    if (rate > 0 && _appStatus.value == AppStatus.CONNECTED) {
                        addChartDataPoint(rate)
                    }
                }
            }

            launch {
                service.speed.collect { _speed.postValue(it) }
            }

            launch {
                service.scanResults.collect { _scanResults.postValue(it) }
            }

            launch {
                service.bleState.collectLatest { state ->
                    _statusMessage.postValue(state.message)
                    val newStatus = when (state) {
                        is BleState.Scanning -> AppStatus.SCANNING
                        is BleState.AutoConnecting, is BleState.Connecting, is BleState.AutoReconnecting -> AppStatus.CONNECTING
                        is BleState.Connected -> AppStatus.CONNECTED
                        else -> AppStatus.DISCONNECTED
                    }

                    if (_appStatus.value != AppStatus.CONNECTED && newStatus == AppStatus.CONNECTED) {
                        initializeChart()
                    }

                    _appStatus.postValue(newStatus)
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

        if (chartDataPoints.size >= MAX_CHART_POINTS) {
            chartDataPoints.removeAt(0)
        }
        chartDataPoints.add(newEntry)
        _newChartEntry.postValue(newEntry)
    }

    // --- Actions delegated to the service ---
    fun startScan() {
        bleServiceRef?.get()?.startScan()
    }

    fun startAutoConnectScan(identifier: String) {
        bleServiceRef?.get()?.startAutoConnectScan(identifier)
    }

    fun connectToDevice(identifier: String) {
        bleServiceRef?.get()?.connectToDevice(identifier)
    }

    fun disconnectDevice() {
        bleServiceRef?.get()?.disconnectDevice()
    }

    // --- Favorite device logic ---
    fun isDeviceFavorite(identifier: String): Boolean {
        return sharedPrefs.getString("favorite_device_id", null) == identifier
    }

    fun toggleFavoriteDevice(ad: Advertisement) {
        val id = ad.identifier
        val currentFavorite = sharedPrefs.getString("favorite_device_id", null)
        val newFavorite = if (currentFavorite == id) null else id
        // 修复：使用 KTX edit 扩展函数替代 edit()...apply()
        sharedPrefs.edit {
            putString("favorite_device_id", newFavorite)
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceDataJob?.cancel()
        bleServiceRef = null
    }
}