package com.example.heart_rate_monitor_mobile.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.db.HeartRateRecord
import com.example.heart_rate_monitor_mobile.data.db.HeartRateSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 历史列表页 ViewModel（数据库访问从 Activity 下沉至此） */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppContainer.get(application).database.heartRateDao()

    val sessions: StateFlow<List<HeartRateSession>?> = dao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun deleteSessions(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteSessionsByIds(ids) }
    }

    /** 单个会话的全部记录（图表页复用本 VM 亦可，独立方法便于按需加载） */
    private val _sessionRecords = MutableStateFlow<List<HeartRateRecord>?>(null)
    val sessionRecords: StateFlow<List<HeartRateRecord>?> = _sessionRecords.asStateFlow()

    fun loadSessionRecords(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _sessionRecords.value = dao.getRecordsForSession(sessionId)
        }
    }
}
