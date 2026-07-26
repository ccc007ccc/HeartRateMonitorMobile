package com.example.heart_rate_monitor_mobile.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 一次记录活动（v3 起不再绑定单一设备）：
 * 主设备连接即开始、主设备断开即结束；期间参与的全部设备（主 + 对比）
 * 都以 [SessionDevice] 挂载在会话下，样本按设备归属。
 */
@Entity(tableName = "recording_sessions")
data class RecordingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
)

/** 会话中的一台参与设备 */
@Entity(
    tableName = "session_devices",
    foreignKeys = [ForeignKey(
        entity = RecordingSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId"])],
)
data class SessionDevice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    /** 蓝牙地址；v2 迁移的历史数据无此信息，为空串 */
    val deviceId: String,
    val deviceName: String,
    val isPrimary: Boolean,
)

/** 会话 + 参与设备（历史列表用） */
data class SessionWithDevices(
    @Embedded val session: RecordingSession,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val devices: List<SessionDevice>,
) {
    val primaryDevice: SessionDevice? get() = devices.firstOrNull { it.isPrimary } ?: devices.firstOrNull()
    val comparisonCount: Int get() = devices.count { !it.isPrimary }
}
