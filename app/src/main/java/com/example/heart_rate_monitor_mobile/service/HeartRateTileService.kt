package com.example.heart_rate_monitor_mobile.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 快速设置（控制中心）磁贴。
 *
 * - 点按：未运行 → 启动 BleService（前台保活 + 自动连接收藏/上次设备）并开启悬浮窗，
 *   磁贴转为 Active；已连接/连接中 → 断开并关闭悬浮窗，磁贴转 Inactive。
 * - 长按：系统自动发送 ACTION_QS_TILE_PREFERENCES，MainActivity 声明了对应
 *   intent-filter，直接打开应用主界面。
 * - 磁贴副标题实时显示连接状态 / 当前 BPM（监听期间由 onStartListening 驱动）。
 *
 * Android 14+ 前台服务启动限制：磁贴点击使应用进入临时豁免窗口，
 * startForegroundService 合规。
 */
class HeartRateTileService : TileService() {

    private val container by lazy { AppContainer.get(this) }
    private var listeningScope: CoroutineScope? = null
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listeningScope = scope
        listeningJob = scope.launch {
            combine(
                container.heartRate.bleState,
                container.heartRate.heartRate,
            ) { state, rate -> state to rate }
                .collect { (state, rate) -> updateTile(state, rate) }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningScope = null
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = container.heartRate.bleState.value
        val active = state is BleState.Connected ||
            state is BleState.Connecting ||
            state is BleState.AutoConnecting ||
            state is BleState.AutoReconnecting

        // 无障碍通道已生效时：进程由无障碍服务保活，无需（也不应）启动前台服务
        val accessibilityActive = container.overlayCoordinator.isAccessibilityChannel()

        if (active) {
            // 已在运行：断开连接、关闭悬浮窗、停止前台服务（含内置服务器）
            container.heartRate.disconnectDevice()
            container.settings.setAsync(SettingsKeys.FLOATING_WINDOW_ENABLED, false)
            // 前台通道：停掉前台服务（连同服务器）。
            // 无障碍通道：仅当 BleService 因历史原因仍在运行时停掉它清除残留通知；
            // 服务器由无障碍服务托管，BleService.onDestroy 已做通道判断不会误停
            if (!accessibilityActive || container.overlayCoordinator.bleServiceRunning) {
                stopService(Intent(this, BleService::class.java))
            }
        } else {
            // 启动心率服务 + 自动连接
            if (accessibilityActive) {
                // 无收藏设备/无上次连接记录时 autoConnect 会返回 false，给出反馈避免"点了没反应"
                if (!container.heartRate.autoConnect()) {
                    Toast.makeText(this, R.string.tile_no_device, Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                try {
                    startForegroundService(
                        Intent(this, BleService::class.java).apply {
                            action = BleService.ACTION_AUTO_CONNECT
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "从磁贴启动 BleService 失败", e)
                    return
                }
            }
            // 开启悬浮窗：无障碍通道免权限且由无障碍服务托管窗口；
            // 前台通道需悬浮窗权限，并先等设置落盘再启动服务（避免首个收集值仍是 false 而自杀）
            if (accessibilityActive || Settings.canDrawOverlays(this)) {
                container.appScope.launch {
                    container.settings.set(SettingsKeys.FLOATING_WINDOW_ENABLED, true)
                    if (!accessibilityActive) {
                        try {
                            startService(
                                Intent(this@HeartRateTileService, FloatingWindowService::class.java)
                            )
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "从磁贴启动悬浮窗失败", e)
                        }
                    }
                }
            }
        }
    }

    private fun updateTile(state: BleState, rate: Int) {
        val tile = qsTile ?: return
        val subtitle: String
        when (state) {
            is BleState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = if (rate > 0) "$rate BPM" else getString(R.string.common_connected)
            }
            is BleState.Connecting, is BleState.AutoConnecting, is BleState.AutoReconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = getString(R.string.tile_connecting)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                subtitle = getString(R.string.tile_tap_to_connect)
            }
        }
        tile.label = getString(R.string.tile_label)
        // Tile.subtitle 为 API 29+；低版本回落到 label 展示状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        } else {
            tile.label = getString(R.string.tile_label) + " · " + subtitle
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_heart)
        tile.updateTile()
    }

    private companion object {
        const val TAG = "HeartRateTileService"
    }
}
