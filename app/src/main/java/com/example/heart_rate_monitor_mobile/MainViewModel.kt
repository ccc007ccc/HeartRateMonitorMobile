package com.example.heart_rate_monitor_mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.juul.kable.Advertisement
import com.juul.kable.ConnectionLostException
import com.juul.kable.Peripheral
import com.juul.kable.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

enum class AppStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private var peripheral: Peripheral? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null

    private val _statusMessage = MutableLiveData("请先扫描并连接设备")
    val statusMessage: LiveData<String> = _statusMessage

    private val _appStatus = MutableLiveData(AppStatus.DISCONNECTED)
    val appStatus: LiveData<AppStatus> = _appStatus

    private val _scanResults = MutableLiveData<List<Advertisement>>(emptyList())
    val scanResults: LiveData<List<Advertisement>> = _scanResults

    private val _heartRate = MutableLiveData(0)
    val heartRate: LiveData<Int> = _heartRate

    fun startScan() {
        if (_appStatus.value == AppStatus.SCANNING) return
        cleanupConnection()
        _appStatus.value = AppStatus.SCANNING
        _statusMessage.value = "正在扫描设备..."
        _scanResults.value = emptyList()

        scanJob?.cancel()
        scanJob = bleManager.scan()
            .onEach { advertisement ->
                val currentList = _scanResults.value ?: emptyList()
                if (currentList.none { it.identifier == advertisement.identifier }) {
                    _scanResults.postValue(currentList + advertisement)
                }
            }
            .launchIn(viewModelScope)
    }

    fun stopScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            if (_appStatus.value == AppStatus.SCANNING) {
                _appStatus.value = AppStatus.DISCONNECTED
                _statusMessage.value = "扫描已停止"
            }
        }
    }

    fun connectToDevice(advertisement: Advertisement) {
        stopScan()
        _appStatus.value = AppStatus.CONNECTING
        _statusMessage.value = "正在连接到 ${advertisement.name ?: "未知设备"}..."
        _scanResults.value = emptyList()

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch { // This launch block creates the scope
            try {
                // ***修正: 使用 this@launch 明确指定外部协程作用域***
                peripheral = bleManager.getPeripheral(advertisement, this).apply {
                    state.onEach { state -> handleConnectionState(state) }.launchIn(this@launch)
                    connect()
                    bleManager.observeHeartRate(this)
                        .onEach { newHeartRate -> _heartRate.postValue(newHeartRate) }
                        .launchIn(this@launch)
                }
            } catch (e: Exception) {
                val message = when (e) {
                    is ConnectionLostException -> "连接丢失"
                    is CancellationException -> "连接已取消"
                    else -> "连接失败: ${e.message}"
                }
                _statusMessage.postValue(message)
                cleanupConnection()
            }
        }
    }

    private fun handleConnectionState(state: State) {
        when (state) {
            is State.Connecting -> _statusMessage.value = "正在建立连接..."
            is State.Connected -> {
                _appStatus.value = AppStatus.CONNECTED
                _statusMessage.value = "已连接，正在接收心率数据..."
            }
            is State.Disconnecting -> _statusMessage.value = "正在断开连接..."
            is State.Disconnected -> {
                _statusMessage.value = "设备连接已断开"
                cleanupConnection()
            }
        }
    }

    fun disconnectDevice() {
        if (peripheral == null && connectionJob == null) return

        _appStatus.value = AppStatus.DISCONNECTING
        _statusMessage.value = "正在断开连接..."

        viewModelScope.launch {
            try {
                peripheral?.disconnect()
            } catch(e: Exception) {
                println("Error during disconnect call: ${e.message}")
            } finally {
                cleanupConnection()
                _statusMessage.value = "已手动断开连接"
            }
        }
    }

    private fun cleanupConnection() {
        connectionJob?.cancel()
        connectionJob = null
        peripheral = null
        _heartRate.postValue(0)
        _appStatus.postValue(AppStatus.DISCONNECTED)
    }



    override fun onCleared() {
        super.onCleared()
        disconnectDevice()
    }
}