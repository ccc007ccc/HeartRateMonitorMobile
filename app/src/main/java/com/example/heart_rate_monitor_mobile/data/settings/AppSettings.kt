package com.example.heart_rate_monitor_mobile.data.settings

import android.graphics.Color

/**
 * 应用全部设置的不可变快照，按功能域分组。
 *
 * 这是全项目设置项与默认值的唯一定义处：任何组件不得再各自声明默认值
 * （历史上 speed_display_enabled 曾在不同文件出现 true/false 两种默认值，
 * 导致新装用户设置页显示关闭但服务仍请求 GPS 的实际 bug）。
 */
data class AppSettings(
    val connection: ConnectionSettings = ConnectionSettings(),
    val general: GeneralSettings = GeneralSettings(),
    val floating: FloatingWindowSettings = FloatingWindowSettings(),
    val statusBar: StatusBarSettings = StatusBarSettings(),
    val alarm: AlarmSettings = AlarmSettings(),
    val server: ServerSettings = ServerSettings(),
    val update: UpdateSettings = UpdateSettings(),
)

/** 更新检查状态（克制策略：24h 节流 + 可跳过某版本） */
data class UpdateSettings(
    val lastCheckAtMs: Long = 0L,
    /** 用户选择"不再提示"的版本号；该版本不再弹窗 */
    val skippedVersion: String = "",
)

data class ConnectionSettings(
    val autoConnectEnabled: Boolean = false,
    val autoReconnectEnabled: Boolean = true,
    val favoriteDeviceId: String? = null,
    /** 最近一次成功连接的设备，持久化以便服务重启后仍能自动重连 */
    val lastConnectedDeviceId: String? = null,
    /** 收藏历史（JSON 数组：[{id,name,timestamp}]，最近的在前，最多 20 条） */
    val favoriteDeviceHistoryJson: String = "[]",
)

data class GeneralSettings(
    val monetColorEnabled: Boolean = true,
    val hideFromRecentsEnabled: Boolean = false,
    val speedDisplayEnabled: Boolean = false,
    val historyRecordingEnabled: Boolean = false,
    val heartbeatAnimationEnabled: Boolean = true,
    /** 多设备对比评测模式（不常用功能，默认关闭，主页零侵扰） */
    val comparisonModeEnabled: Boolean = false,
    /** 保活通道：见 [KeepAliveChannel]。默认前台服务（兼容性最好） */
    val keepAliveChannel: KeepAliveChannel = KeepAliveChannel.FOREGROUND,
)

/**
 * 保活与悬浮窗渲染通道。
 * - [FOREGROUND]：前台服务保活 + TYPE_APPLICATION_OVERLAY（需悬浮窗权限，通知栏常驻一条通知）
 * - [ACCESSIBILITY]：无障碍服务保活 + TYPE_ACCESSIBILITY_OVERLAY（无通知、免悬浮窗权限、抗杀后台）
 */
enum class KeepAliveChannel {
    FOREGROUND,
    ACCESSIBILITY;

    companion object {
        fun fromKey(key: String?): KeepAliveChannel =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: FOREGROUND
    }
}

data class FloatingWindowSettings(
    val enabled: Boolean = false,
    val bpmTextEnabled: Boolean = true,
    val heartIconEnabled: Boolean = true,
    val sizePercent: Int = 100,
    val iconSizePercent: Int = 100,
    val textColor: Int = Color.BLACK,
    val backgroundColor: Int = Color.BLACK,
    val backgroundAlphaPercent: Int = 10,
    val borderColor: Int = Color.GRAY,
    val borderAlphaPercent: Int = 100,
    val cornerRadius: Int = 100,
)

data class StatusBarSettings(
    val residentEnabled: Boolean = false,
    val bpmTextEnabled: Boolean = true,
    val autoColor: Boolean = false,
    val whiteText: Boolean = false,
    val sizePercent: Int = 100,
    val textThickness: Int = 0,
    val xPositionPercent: Int = 0,
    /** 0-20，中值 10 表示 0dp 垂直偏移 */
    val yOffset: Int = 10,
)

data class AlarmSettings(
    val enabled: Boolean = false,
    val highThreshold: Int = 100,
    val lowThreshold: Int = 50,
    val durationSeconds: Int = 10,
    val repeatEnabled: Boolean = false,
    val repeatIntervalMinutes: Int = 5,
    val postureCalibrationJson: String? = null,
)

data class ServerSettings(
    val httpEnabled: Boolean = false,
    val httpPort: Int = 8000,
    val webSocketEnabled: Boolean = false,
    val webSocketPort: Int = 8001,
    /**
     * 是否要求 Token 认证（默认关闭）。本项目定位家庭局域网生态
     * （HeartRateWidget / 桌面版直连，无认证能力），服务器始终绑定所有网卡；
     * 需要防护时可手动开启本开关。
     */
    val authRequired: Boolean = false,
    /** 认证 token，开启 Token 认证时自动生成，可在服务器页重置 */
    val authToken: String = "",
)
