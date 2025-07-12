package com.example.heart_rate_monitor_mobile.ui.main

import android.app.Application
import androidx.lifecycle.*
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.service.BleService
import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("app_settings", Application.MODE_PRIVATE)
    private var bleService: BleService? = null

    // --- LiveData derived from Service's StateFlow ---
    private val _statusMessage = MutableLiveData("Click button below to scan")
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _appStatus = MutableLiveData(AppStatus.DISCONNECTED)
    val appStatus: LiveData<AppStatus> get() = _appStatus

    // Late-init properties for Flows that depend on the service
    lateinit var heartRate: LiveData<Int>
    lateinit var scanResults: LiveData<List<Advertisement>>

    fun setBleService(service: BleService) {
        this.bleService = service

        // Now that the service is available, initialize the LiveData streams
        initializeDataStreams(service)
    }

    private fun initializeDataStreams(service: BleService) {
        heartRate = service.heartRate.asLiveData()

        scanResults = service.scanResults.asLiveData()

        viewModelScope.launch {
            service.bleState.collect { state ->
                _statusMessage.value = state.message
                _appStatus.value = when (state) {
                    is BleState.Scanning -> AppStatus.SCANNING
                    // 【关键修改】将 AutoConnecting 状态映射到 CONNECTING UI状态
                    is BleState.AutoConnecting, is BleState.Connecting -> AppStatus.CONNECTING
                    is BleState.Connected -> AppStatus.CONNECTED
                    else -> AppStatus.DISCONNECTED
                }
            }
        }
    }

    // --- Actions delegated to the service ---
    fun startScan() {
        bleService?.startScan()
    }

    /**
     * 【新增方法】启动自动连接扫描流程
     */
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