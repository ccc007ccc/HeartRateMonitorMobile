package com.example.heart_rate_monitor_mobile.data.db

import android.util.Log
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 多轨会话记录器（v3）：主设备连接即开会话、断开即结束；
 * 会话期间主设备与全部对比设备的样本都入库，按设备分轨归属。
 *
 * 事件经无界 Channel 由单一消费协程串行处理（顺序与到达一致）；
 * 样本缓冲攒 [BATCH_SIZE] 条或 [FLUSH_INTERVAL_MS] 单事务批量落盘
 * （1Hz 单设备下事务数降约 30 倍；多设备同录时批次更满、效率更高）。
 * history_recording_enabled 每样本实时读取，中途切换立即生效。
 */
class SessionRecorder(
    private val dao: HeartRateDao,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private sealed interface Event {
        data class PrimaryConnected(val deviceId: String, val deviceName: String) : Event
        data class Sample(
            val deviceKey: String,
            val deviceName: String,
            val isPrimary: Boolean,
            val bpm: Int,
            val timestampMs: Long,
            val rr: String?,
        ) : Event

        data object PrimaryDisconnected : Event
        data object CleanupOpenSessions : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private var currentSessionId: Long? = null

    /** deviceKey → session_devices 行 id（懒创建：设备首个样本时挂入会话） */
    private val deviceRowIds = mutableMapOf<String, Long>()
    private val pendingRecords = mutableListOf<HeartRateRecord>()
    private var lastFlushAt = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: Exception) {
                    Log.e(TAG, "处理会话事件失败: $event", e)
                }
            }
        }
    }

    fun onPrimaryConnected(deviceId: String, deviceName: String) {
        events.trySend(Event.PrimaryConnected(deviceId, deviceName))
    }

    fun onSample(
        deviceKey: String,
        deviceName: String,
        isPrimary: Boolean,
        bpm: Int,
        timestampMs: Long = System.currentTimeMillis(),
        rrIntervalsMs: List<Int> = emptyList(),
    ) {
        if (bpm <= 0) return
        events.trySend(
            Event.Sample(
                deviceKey, deviceName, isPrimary, bpm, timestampMs,
                rrIntervalsMs.takeIf { it.isNotEmpty() }?.joinToString(","),
            )
        )
    }

    fun onPrimaryDisconnected() {
        events.trySend(Event.PrimaryDisconnected)
    }

    /** 进程冷启动时兜底关闭上次异常退出遗留的未结束会话 */
    fun cleanupOpenSessions() {
        events.trySend(Event.CleanupOpenSessions)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.PrimaryConnected -> {
                if (!settings.settings.value.general.historyRecordingEnabled) return
                if (currentSessionId != null) return
                val sessionId = dao.insertSession(RecordingSession(startTime = System.currentTimeMillis()))
                currentSessionId = sessionId
                deviceRowIds[event.deviceId] = dao.insertSessionDevice(
                    SessionDevice(
                        sessionId = sessionId,
                        deviceId = event.deviceId,
                        deviceName = event.deviceName,
                        isPrimary = true,
                    )
                )
                lastFlushAt = System.currentTimeMillis()
            }
            is Event.Sample -> {
                if (!settings.settings.value.general.historyRecordingEnabled) return
                val sessionId = currentSessionId ?: return
                val rowId = deviceRowIds.getOrPut(event.deviceKey) {
                    dao.insertSessionDevice(
                        SessionDevice(
                            sessionId = sessionId,
                            deviceId = event.deviceKey,
                            deviceName = event.deviceName,
                            isPrimary = event.isPrimary,
                        )
                    )
                }
                pendingRecords.add(
                    HeartRateRecord(
                        sessionDeviceId = rowId,
                        timestamp = event.timestampMs,
                        heartRate = event.bpm,
                        rr = event.rr,
                    )
                )
                val now = System.currentTimeMillis()
                if (pendingRecords.size >= BATCH_SIZE || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                    flushPending()
                }
            }
            is Event.PrimaryDisconnected -> {
                flushPending()
                currentSessionId?.let { id ->
                    dao.endSession(id, System.currentTimeMillis())
                }
                currentSessionId = null
                deviceRowIds.clear()
            }
            is Event.CleanupOpenSessions -> {
                flushPending()
                for (session in dao.getOpenSessions()) {
                    if (session.id == currentSessionId) continue
                    val lastTimestamp = dao.getLastRecordTimestampForSession(session.id)
                    dao.endSession(session.id, lastTimestamp ?: session.startTime)
                }
            }
        }
    }

    private suspend fun flushPending() {
        if (pendingRecords.isEmpty()) return
        try {
            dao.insertRecords(pendingRecords.toList())
        } catch (e: Exception) {
            Log.w(TAG, "样本批量落盘失败，丢弃本批缓冲", e)
        }
        pendingRecords.clear()
        lastFlushAt = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "SessionRecorder"
        const val BATCH_SIZE = 30
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}
