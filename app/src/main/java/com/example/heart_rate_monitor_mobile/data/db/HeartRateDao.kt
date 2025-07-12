package com.example.heart_rate_monitor_mobile.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HeartRateDao {
    @Insert
    suspend fun insertSession(session: HeartRateSession): Long

    @Query("UPDATE heart_rate_sessions SET endTime = :endTime WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endTime: Long)

    @Insert
    suspend fun insertRecord(record: HeartRateRecord)

    @Query("SELECT * FROM heart_rate_sessions ORDER BY startTime DESC")
    fun getAllSessions(): LiveData<List<HeartRateSession>>

    @Query("SELECT * FROM heart_rate_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getRecordsForSession(sessionId: Long): List<HeartRateRecord>

    @Query("DELETE FROM heart_rate_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}