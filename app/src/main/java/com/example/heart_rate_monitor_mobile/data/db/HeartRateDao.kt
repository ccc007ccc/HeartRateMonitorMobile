package com.example.heart_rate_monitor_mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Insert
    suspend fun insertSession(session: RecordingSession): Long

    @Insert
    suspend fun insertSessionDevice(device: SessionDevice): Long

    @Query("UPDATE recording_sessions SET endTime = :endTime WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endTime: Long)

    /** 批量写入（单事务）：SessionRecorder 缓冲落盘用，避免 1Hz 逐条提交的写放大 */
    @Insert
    suspend fun insertRecords(records: List<HeartRateRecord>)

    @Transaction
    @Query("SELECT * FROM recording_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SessionWithDevices>>

    @Query("SELECT * FROM session_devices WHERE sessionId = :sessionId ORDER BY isPrimary DESC, id ASC")
    suspend fun getDevicesForSession(sessionId: Long): List<SessionDevice>

    @Query("SELECT * FROM heart_rate_records WHERE sessionDeviceId = :sessionDeviceId ORDER BY timestamp ASC")
    suspend fun getRecordsForDevice(sessionDeviceId: Long): List<HeartRateRecord>

    @Query("DELETE FROM recording_sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessionsByIds(sessionIds: List<Long>)

    @Query("SELECT * FROM recording_sessions WHERE endTime IS NULL")
    suspend fun getOpenSessions(): List<RecordingSession>

    /** 会话（跨全部参与设备）最后一条样本时间，用于异常退出后的兜底收尾 */
    @Query(
        "SELECT MAX(r.timestamp) FROM heart_rate_records r " +
            "JOIN session_devices d ON r.sessionDeviceId = d.id WHERE d.sessionId = :sessionId"
    )
    suspend fun getLastRecordTimestampForSession(sessionId: Long): Long?
}
