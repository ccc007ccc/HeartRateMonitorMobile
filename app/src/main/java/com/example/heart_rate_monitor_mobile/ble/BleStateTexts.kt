package com.example.heart_rate_monitor_mobile.ble

import android.content.Context
import com.example.heart_rate_monitor_mobile.R

/**
 * BleState → 用户可见文案 / 对外接口状态码的唯一映射处。
 *
 * displayText 被 UI 与 HTTP/WS 接口的 status 字段共用（跟随系统语言）；
 * statusKey 为机器可读状态码，供外部集成方稳定消费。
 */
object BleStateTexts {

    fun displayText(context: Context, state: BleState): String = when (state) {
        is BleState.Idle -> context.getString(R.string.ble_state_idle)
        is BleState.Scanning -> context.getString(R.string.ble_state_scanning)
        is BleState.ScanFinished -> if (state.foundAny) {
            context.getString(R.string.ble_state_scan_finished)
        } else {
            context.getString(R.string.ble_state_scan_no_devices)
        }
        is BleState.Connecting -> context.getString(R.string.ble_state_connecting)
        is BleState.AutoConnecting -> context.getString(R.string.ble_state_auto_connecting)
        is BleState.AutoReconnecting ->
            context.getString(R.string.ble_state_auto_reconnecting, state.attempt)
        is BleState.Connected -> context.getString(R.string.ble_state_connected_to, state.deviceName)
        is BleState.Disconnected -> when (state.reason) {
            BleState.DisconnectReason.MANUAL ->
                context.getString(R.string.ble_state_disconnected_manual)
            BleState.DisconnectReason.CONNECTION_LOST ->
                context.getString(R.string.ble_state_connection_lost)
            BleState.DisconnectReason.CONNECT_FAILED ->
                context.getString(R.string.ble_state_connect_failed)
            BleState.DisconnectReason.RECONNECT_NOT_FOUND ->
                context.getString(R.string.ble_state_reconnect_not_found)
        }
    }

    fun statusKey(state: BleState): String = when (state) {
        is BleState.Idle -> "idle"
        is BleState.Scanning -> "scanning"
        is BleState.ScanFinished -> "scan_finished"
        is BleState.Connecting -> "connecting"
        is BleState.AutoConnecting -> "auto_connecting"
        is BleState.AutoReconnecting -> "reconnecting"
        is BleState.Connected -> "connected"
        is BleState.Disconnected -> "disconnected"
    }
}
