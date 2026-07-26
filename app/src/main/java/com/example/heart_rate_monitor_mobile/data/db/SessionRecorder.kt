package com.example.heart_rate_monitor_mobile.data.db

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 心率历史会话记录器（从 BleService 抽出的数据库写入职责）。
 *
 * 事件经无界 Channel 由单一消费协程串行处理——保证 连接→采样→断开 的
 * 处理顺序与事件到达顺序一致（每事件独立 launch + mutex 无法保证顺序）。
 *
 * 写入策略：样本先进内存缓冲，攒满 [BATCH_SIZE] 条或距上次落盘超过
 * [FLUSH_INTERVAL_MS] 时才单事务批量写入——1Hz 采样下事务提交数降约 30 倍，
 * 这是"开启历史记录更耗电"的主要来源。断开/清理时强制 flush；
 * 进程被杀最多丢一批缓冲（≤30 秒），对历史曲线可接受。
 *
 * 与旧实现的行为差异（修复）：
 * - history_recording_enabled 在每个样本写入时实时读取，连接中途切换开关立即生效
 *   （旧实现只在订阅开始时读一次）。
 */
class SessionRecorder(
    private val dao: HeartRateDao,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private sealed interface Event {
        data class Connected(val deviceName: String) : Event
        data class Sample(val bpm: Int, val timestampMs: Long) : Event
        data object Disconnected : Event
        data object CleanupOpenSessions : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var currentSessionId: Long? = null

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

    fun onConnected(deviceName: String) {
        events.trySend(Event.Connected(deviceName))
    }

    /** [timestampMs] 为样本产生时刻（BLE 发射点打点），落库时间戳与真实采样对齐 */
    fun onSample(bpm: Int, timestampMs: Long = System.currentTimeMillis()) {
        if (bpm <= 0) return
        events.trySend(Event.Sample(bpm, timestampMs))
    }

    fun onDisconnected() {
        events.trySend(Event.Disconnected)
    }

    /** 进程冷启动时兜底关闭上次异常退出遗留的未结束会话 */
    fun cleanupOpenSessions() {
        events.trySend(Event.CleanupOpenSessions)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connected -> {
                if (!settings.settings.value.general.historyRecordingEnabled) return
                if (currentSessionId != null) return
                currentSessionId = dao.insertSession(
                    HeartRateSession(deviceName = event.deviceName, startTime = System.currentTimeMillis())
                )
                lastFlushAt = System.currentTimeMillis()
            }
            is Event.Sample -> {
                if (!settings.settings.value.general.historyRecordingEnabled) return
                val sessionId = currentSessionId ?: return
                pendingRecords.add(
                    HeartRateRecord(
                        sessionId = sessionId,
                        timestamp = event.timestampMs,
                        heartRate = event.bpm,
                    )
                )
                val now = System.currentTimeMillis()
                if (pendingRecords.size >= BATCH_SIZE || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                    flushPending()
                }
            }
            is Event.Disconnected -> {
                flushPending()
                currentSessionId?.let { id ->
                    dao.endSession(id, System.currentTimeMillis())
                    currentSessionId = null
                }
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
        } catch (e: SQLiteConstraintException) {
            Log.w(TAG, "会话已失效，丢弃缓冲并停止写入", e)
            currentSessionId = null
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
