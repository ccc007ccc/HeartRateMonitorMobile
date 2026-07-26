package com.example.heart_rate_monitor_mobile.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.db.SessionWithDevices
import com.example.heart_rate_monitor_mobile.domain.AccuracyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 历史页 ViewModel（v3：会话-设备-样本三层） */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppContainer.get(application).database.heartRateDao()

    val sessions: StateFlow<List<SessionWithDevices>?> = dao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun deleteSessions(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteSessionsByIds(ids) }
    }

    // ---------- 会话详情（图表页） ----------

    /** 一台设备的曲线数据 + 相对主设备的准度报告 */
    data class DeviceSeries(
        val deviceRowId: Long,
        val name: String,
        val isPrimary: Boolean,
        val points: List<Pair<Long, Int>>,
        val accuracy: AccuracyReport?,
    )

    private val _sessionDetail = MutableStateFlow<List<DeviceSeries>?>(null)
    val sessionDetail: StateFlow<List<DeviceSeries>?> = _sessionDetail.asStateFlow()

    fun loadSessionDetail(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val devices = dao.getDevicesForSession(sessionId)
            val seriesByDevice = devices.map { device ->
                device to dao.getRecordsForDevice(device.id).map { it.timestamp to it.heartRate }
            }
            val primaryPoints = seriesByDevice.firstOrNull { it.first.isPrimary }?.second.orEmpty()
            _sessionDetail.value = seriesByDevice.map { (device, points) ->
                DeviceSeries(
                    deviceRowId = device.id,
                    name = device.deviceName,
                    isPrimary = device.isPrimary,
                    points = points,
                    accuracy = if (!device.isPrimary && primaryPoints.isNotEmpty()) {
                        AccuracyReport.compute(primaryPoints, points)
                    } else null,
                )
            }
        }
    }
}
