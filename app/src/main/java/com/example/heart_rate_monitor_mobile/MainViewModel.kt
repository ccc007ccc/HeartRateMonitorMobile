package com.example.heart_rate_monitor_mobile

import android.app.Application
import androidx.lifecycle.*
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean


enum class AppStatus {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application.applicationContext)

    // --- LiveData for UI updates ---
    private val _scanResults = MutableLiveData<List<Advertisement>>(emptyList())
    val scanResults: LiveData<List<Advertisement>> get() = _scanResults

    private val _statusMessage = MutableLiveData<String>("未连接")
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _heartRate = MutableLiveData<Int>(0)
    val heartRate: LiveData<Int> get() = _heartRate

    private val _appStatus = MutableLiveData<AppStatus>(AppStatus.DISCONNECTED)
    val appStatus: LiveData<AppStatus> get() = _appStatus

    // --- Connection and Job Management ---
    private var connectedPeripheral: Peripheral? = null
    private var scanJob: Job? = null
    private var monitorJob: Job? = null
    private val isConnecting = AtomicBoolean(false) // 原子操作，防止重复连接

    // --- SharedPreferences for Favorites ---
    private val sharedPrefs = application.getSharedPreferences("app_settings", Application.MODE_PRIVATE)

    private fun getFavoriteDevice(): String? {
        return sharedPrefs.getString("favorite_device_id", null)
    }

    fun isDeviceFavorite(identifier: String): Boolean {
        return getFavoriteDevice() == identifier
    }

    fun toggleFavoriteDevice(ad: Advertisement) {
        val id = ad.identifier
        val newFavorite = if (getFavoriteDevice() == id) null else id

        with(sharedPrefs.edit()) {
            putString("favorite_device_id", newFavorite)
            apply()
        }
    }

    // --- Core BLE Functions ---

    /**
     * 开始扫描蓝牙设备
     */
    fun startScan() {
        if (_appStatus.value == AppStatus.SCANNING || _appStatus.value == AppStatus.CONNECTING) return

        cleanupConnection() // 开始扫描前，确保旧连接已清理
        _appStatus.value = AppStatus.SCANNING
        _statusMessage.value = "正在扫描设备..."
        _scanResults.value = emptyList()

        scanJob?.cancel() // 取消任何正在进行的扫描
        scanJob = viewModelScope.launch {
            bleManager.scan()
                .onCompletion {
                    // 当扫描流结束时（例如，被取消），如果状态仍然是“扫描中”，则重置为“未连接”
                    if (_appStatus.value == AppStatus.SCANNING) {
                        _appStatus.postValue(AppStatus.DISCONNECTED)
                        _statusMessage.postValue("扫描结束")
                    }
                }
                .collect { ad ->
                    // 更新扫描结果列表
                    val currentList = _scanResults.value?.toMutableList() ?: mutableListOf()
                    if (currentList.none { it.identifier == ad.identifier }) {
                        currentList.add(ad)
                        _scanResults.postValue(currentList)
                    }

                    // 检查是否需要自动连接收藏的设备
                    val isAutoConnectEnabled = sharedPrefs.getBoolean("auto_connect_enabled", false)
                    if (isAutoConnectEnabled && isDeviceFavorite(ad.identifier)) {
                        connectToDevice(ad) // 找到收藏的设备，立即连接
                    }
                }
        }
    }

    /**
     * 连接到指定的蓝牙设备
     */
    fun connectToDevice(ad: Advertisement) {
        // 使用原子布尔值防止并发连接。如果isConnecting已为true，则不执行任何操作。
        if (!isConnecting.compareAndSet(false, true)) {
            return
        }

        // 停止扫描，因为我们即将开始连接
        scanJob?.cancel()

        _appStatus.value = AppStatus.CONNECTING
        _statusMessage.value = "正在连接 ${ad.name ?: "设备"}..."

        viewModelScope.launch {
            try {
                // 创建Peripheral实例
                val peripheral = bleManager.getPeripheral(ad, viewModelScope)
                connectedPeripheral = peripheral

                // 启动一个独立的协程来监控连接状态
                // 这是处理意外断开的关键
                monitorJob = launch { monitorConnection(peripheral) }

                // 设置10秒的连接超时
                withTimeout(10_000) {
                    peripheral.connect()
                }

                // 连接成功后，开始观察心率数据
                observeHeartRateData(peripheral)

            } catch (e: TimeoutCancellationException) {
                _statusMessage.postValue("连接超时，请靠近设备后重试")
                cleanupConnection()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _statusMessage.postValue("连接失败: ${e.message}")
                }
                cleanupConnection()
            } finally {
                // 操作结束后，重置连接标志
                isConnecting.set(false)
            }
        }
    }

    /**
     * 持续监控设备连接状态，处理意外断开
     */
    private fun CoroutineScope.monitorConnection(peripheral: Peripheral) {
        peripheral.state
            .filter { it is State.Disconnected } // 我们只关心“断开”状态
            .onEach {
                // 一旦检测到断开（无论是何种原因），就执行清理
                _statusMessage.postValue("设备连接已断开")
                cleanupConnection()
            }
            .launchIn(this) // 在指定的协程作用域内启动
    }

    /**
     * 订阅并解析心率数据
     */
    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        // 确认设备已连接
        _appStatus.postValue(AppStatus.CONNECTED)
        _statusMessage.postValue("已连接到 ${peripheral.name ?: "设备"}")

        bleManager.observeHeartRate(peripheral)
            .catch { e ->
                _statusMessage.postValue("心率读取错误: ${e.message}")
                cleanupConnection()
            }
            .collect { rate ->
                _heartRate.postValue(rate)
            }
    }

    /**
     * 主动断开当前连接的设备
     */
    fun disconnectDevice() {
        viewModelScope.launch {
            try {
                connectedPeripheral?.disconnect()
            } catch (e: Exception) {
                // 忽略断开时可能发生的异常
            } finally {
                cleanupConnection()
                _statusMessage.postValue("已手动断开连接")
            }
        }
    }

    /**
     * 清理所有连接相关的资源和状态
     */
    private fun cleanupConnection() {
        // 取消所有正在运行的任务
        scanJob?.cancel()
        monitorJob?.cancel()

        // 重置外设和状态
        connectedPeripheral = null
        scanJob = null
        monitorJob = null

        // 更新UI状态到“未连接”
        if (_appStatus.value != AppStatus.DISCONNECTED) {
            _appStatus.postValue(AppStatus.DISCONNECTED)
        }
        _heartRate.postValue(0)
        isConnecting.set(false) // 重置连接锁
    }

    override fun onCleared() {
        super.onCleared()
        cleanupConnection()
    }
}