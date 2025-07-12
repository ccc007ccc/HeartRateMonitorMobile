package com.example.heart_rate_monitor_mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "heart_rate_sessions")
data class HeartRateSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceName: String,
    val startTime: Long,
    var endTime: Long? = null
)