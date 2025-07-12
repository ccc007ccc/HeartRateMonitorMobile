package com.example.heart_rate_monitor_mobile

import android.app.Application
import androidx.lifecycle.*
import com.juul.kable.Advertisement

enum class AppStatus {
    DISCONNECTED, // 包括空闲、扫描结束、连接失败等
    SCANNING,
    CONNECTING,
    CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // LiveData 用于驱动UI更新
    private val _scanResults = MutableLiveData<List<Advertisement>>(emptyList())
    val scanResults: LiveData<List<Advertisement>> get() = _scanResults

    private val _statusMessage = MutableLiveData<String>("点击右下角按钮扫描设备")
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _heartRate = MutableLiveData<Int>(0)
    val heartRate: LiveData<Int> get() = _heartRate

    private val _appStatus = MutableLiveData<AppStatus>(AppStatus.DISCONNECTED)
    val appStatus: LiveData<AppStatus> get() = _appStatus

    private val sharedPrefs = application.getSharedPreferences("app_settings", Application.MODE_PRIVATE)

    // --- 公开的状态更新方法 ---
    fun updateHeartRate(rate: Int) {
        _heartRate.postValue(rate)
    }

    fun updateAppStatus(status: AppStatus) {
        // 当进入扫描状态时，清空上一次的列表
        if (status == AppStatus.SCANNING) {
            _scanResults.value = emptyList()
        }
        _appStatus.postValue(status)
    }

    fun updateStatusMessage(message: String) {
        _statusMessage.postValue(message)
    }

    // --- 扫描结果列表管理 ---
    fun addScanResult(advertisement: Advertisement) {
        val currentList = _scanResults.value?.toMutableList() ?: mutableListOf()
        if (currentList.none { it.identifier == advertisement.identifier }) {
            currentList.add(advertisement)
            _scanResults.postValue(currentList)
        }
    }

    fun clearScanResults() {
        _scanResults.postValue(emptyList())
    }

    // --- 收藏夹功能 ---
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