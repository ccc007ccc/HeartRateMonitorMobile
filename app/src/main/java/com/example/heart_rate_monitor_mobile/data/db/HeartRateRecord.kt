package com.example.heart_rate_monitor_mobile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 心率样本：归属会话内的某台设备；rr 为该帧携带的 RR 间期（毫秒，逗号分隔，可空） */
@Entity(
    tableName = "heart_rate_records",
    foreignKeys = [ForeignKey(
        entity = SessionDevice::class,
        parentColumns = ["id"],
        childColumns = ["sessionDeviceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionDeviceId"])],
)
data class HeartRateRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionDeviceId: Long,
    val timestamp: Long,
    val heartRate: Int,
    val rr: String? = null,
)
