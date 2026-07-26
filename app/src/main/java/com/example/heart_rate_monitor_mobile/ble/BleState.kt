package com.example.heart_rate_monitor_mobile.ble

/**
 * 蓝牙连接状态机的纯状态表示（单一事实来源）。
 *
 * 不携带任何 UI 文案——展示文本由 UI 层按状态映射
 * （见 ui/BleStateText.kt），HTTP/WS 对外接口同样经映射层输出，
 * 数据层与展示层解耦。
 */
sealed class BleState {
    /** 空闲/初始状态 */
    data object Idle : BleState()

    /** 手动扫描中 */
    data object Scanning : BleState()

    /** 扫描结束：found 表示是否找到了任何设备 */
    data class ScanFinished(val foundAny: Boolean) : BleState()

    /** 手动连接中 */
    data object Connecting : BleState()

    /** 应用启动时对收藏设备的自动连接 */
    data object AutoConnecting : BleState()

    /** 意外断开后的自动重连（含扫描与退避等待全过程） */
    data class AutoReconnecting(val attempt: Int) : BleState()

    data class Connected(val deviceName: String) : BleState()

    data class Disconnected(val reason: DisconnectReason) : BleState()

    enum class DisconnectReason {
        /** 用户手动断开 */
        MANUAL,
        /** 连接意外丢失 */
        CONNECTION_LOST,
        /** 连接尝试失败（含超时） */
        CONNECT_FAILED,
        /** 自动重连未找到设备（重连循环仍会继续） */
        RECONNECT_NOT_FOUND,
    }
}
