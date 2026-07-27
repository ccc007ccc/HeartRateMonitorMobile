package com.example.heart_rate_monitor_mobile.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.heart_rate_monitor_mobile.core.AppContainer
import android.util.Log
import com.example.heart_rate_monitor_mobile.data.settings.KeepAliveChannel
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.service.overlay.FloatingWindowHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 无障碍保活通道（v2.2，用户可选）。
 *
 * 用途仅两点，**不读取任何屏幕内容**（配置声明 canRetrieveWindowContent=false）：
 * 1. 借系统对无障碍服务的绑定获得稳定进程优先级——免前台通知、抗国产 ROM 杀后台；
 * 2. 用 TYPE_ACCESSIBILITY_OVERLAY 渲染悬浮窗/状态栏心率——免悬浮窗权限、层级更高
 *    （可覆盖游戏沉浸界面与系统状态栏）。
 *
 * 监听的窗口状态事件只用于感知"通知面板/快捷设置是否展开"，
 * 以便临时隐藏状态栏 overlay（该层级会浮在下拉面板之上）。
 */
class HeartRateAccessibilityService : AccessibilityService() {

    private val container by lazy { AppContainer.get(this) }
    /** onUnbind 会 cancel，系统可能重新绑定同一实例，故 scope 需可重建 */
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var floatingHost: FloatingWindowHost? = null
    /** 通道收集器：onServiceConnected 可能被系统重复回调，用它保证幂等 */
    private var channelJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        container.overlayCoordinator.setAccessibilityActive(true, this)

        val isChannelOwner = container.settings.settings.value.general.keepAliveChannel ==
            KeepAliveChannel.ACCESSIBILITY

        // 本通道为属主时才接管服务器（用户切回前台通道但没关无障碍时不应擅自开启）
        if (isChannelOwner) {
            container.serverController.start()
            // 冷启动补启：ContentProvider 初始化早于本回调，那时无障碍标记必为 false，
            // 状态栏/预警服务会被权限或后台启动限制挡下。此刻进程已是 BOUND_FOREGROUND_SERVICE，
            // 是最安全的启动窗口，在此按设置补启。
            restoreDependentServices()
        }

        // 清掉前台通道遗留的 BleService 及其常驻通知——无障碍模式的卖点就是"无通知保活"。
        // 服务器不会被误停：BleService.onDestroy 已按通道判断跳过 stop()
        if (container.settings.settings.value.general.keepAliveChannel ==
            KeepAliveChannel.ACCESSIBILITY && container.overlayCoordinator.bleServiceRunning
        ) {
            stopService(Intent(this, BleService::class.java))
        }

        // 悬浮窗：仅在选择了无障碍通道时由本服务托管（与 FloatingWindowService 互斥）
        channelJob?.cancel()
        channelJob = scope.launch {
            container.settings.flowOf { it.general.keepAliveChannel }.collectLatest { channel ->
                if (channel == KeepAliveChannel.ACCESSIBILITY) {
                    if (floatingHost == null) {
                        floatingHost = FloatingWindowHost(
                            context = this@HeartRateAccessibilityService,
                            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            isAccessibilityHost = true,
                        )
                    }
                } else {
                    floatingHost?.release()
                    floatingHost = null
                }
            }
        }

        // 进程被系统绑定后顺带恢复自动连接（等价前台服务通道的 AUTO_CONNECT）
        val connection = container.settings.settings.value.connection
        if (isChannelOwner && connection.autoConnectEnabled && connection.favoriteDeviceId != null) {
            container.heartRate.autoConnect()
        }
    }

    /** 按设置补启状态栏常驻 / 心率预警（两者在本通道下均不发常驻通知） */
    private fun restoreDependentServices() {
        val settings = container.settings.settings.value
        if (settings.statusBar.residentEnabled) {
            runCatching { startService(Intent(this, StatusBarResidentService::class.java)) }
                .onFailure { Log.w(TAG, "补启状态栏常驻失败", it) }
        }
        if (settings.alarm.enabled) {
            runCatching { startService(Intent(this, HeartRateAlarmService::class.java)) }
                .onFailure { Log.w(TAG, "补启心率预警失败", it) }
        }
    }

    /**
     * 系统 UI（通知面板/快捷设置）展开时通知状态栏 overlay 临时让位——
     * TYPE_ACCESSIBILITY_OVERLAY 层级高于下拉面板，不让位会盖住面板内容。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val expanded = pkg == SYSTEM_UI_PACKAGE
        container.overlayCoordinator.setSystemUiExpanded(expanded)
        // 兜底：系统 UI 事件也可能来自音量条/heads-up 等瞬时窗口，且之后未必有非 systemui 事件
        // 复位；超时自动恢复，避免状态栏 overlay 长期隐藏
        expandedResetJob?.cancel()
        if (expanded) {
            expandedResetJob = scope.launch {
                delay(SYSTEM_UI_EXPANDED_TIMEOUT_MS)
                container.overlayCoordinator.setSystemUiExpanded(false)
            }
        }
    }

    private var expandedResetJob: kotlinx.coroutines.Job? = null

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        val wasChannelOwner = container.overlayCoordinator.isAccessibilityChannel()
        floatingHost?.release()
        floatingHost = null
        // 本通道曾接管内置服务器：BleService 未运行时一并停掉，避免用户以为已关闭却仍可访问
        if (!container.overlayCoordinator.bleServiceRunning) {
            container.serverController.stop()
        }
        container.overlayCoordinator.setAccessibilityActive(false)
        container.overlayCoordinator.setSystemUiExpanded(false)
        // 系统侧被关闭（含 ROM 重启后自动关闭）：回退前台服务通道，
        // 否则悬浮窗/状态栏/服务器全部消失且进程降为 cached，BLE 静默断连
        if (wasChannelOwner) fallbackToForegroundChannel()
        channelJob?.cancel()
        channelJob = null
        scope.cancel()
    }

    /**
     * 回退到前台服务通道：写回设置并拉起前台服务，把保活责任交还。
     * 此刻进程仍处于 BOUND_FOREGROUND_SERVICE 状态，是可以合法启动前台服务的窗口期。
     */
    private fun fallbackToForegroundChannel() {
        container.settings.setAsync(
            SettingsKeys.KEEP_ALIVE_CHANNEL, KeepAliveChannel.FOREGROUND.name
        )
        val settings = container.settings.settings.value
        runCatching {
            startForegroundService(Intent(this, BleService::class.java))
        }.onFailure { Log.w(TAG, "回退启动 BleService 失败", it) }
        if (settings.floating.enabled) {
            runCatching { startService(Intent(this, FloatingWindowService::class.java)) }
                .onFailure { Log.w(TAG, "回退启动悬浮窗服务失败", it) }
        }
    }

    private companion object {
        const val TAG = "HrAccessibilityService"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SYSTEM_UI_EXPANDED_TIMEOUT_MS = 8_000L
    }
}
