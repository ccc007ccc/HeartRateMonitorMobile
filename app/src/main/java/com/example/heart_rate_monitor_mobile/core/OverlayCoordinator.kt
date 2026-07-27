package com.example.heart_rate_monitor_mobile.core

import android.content.Context
import com.example.heart_rate_monitor_mobile.data.settings.KeepAliveChannel
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗/状态栏 overlay 的通道协调器（进程级单例）。
 *
 * 两条渲染通道（前台服务 / 无障碍服务）互斥托管窗口，此处登记当前活跃通道，
 * 使各方（磁贴、设置页、悬浮窗服务、广播接收器）能感知"无障碍是否已生效"，
 * 并把一次性动作（如关闭触摸穿透）路由到正在渲染的宿主。
 */
class OverlayCoordinator(private val settings: SettingsRepository) {

    /**
     * 无障碍是否为**当前生效通道**：系统侧已开启 且 用户选择了该通道。
     *
     * 判断"谁是属主"（服务器归属、是否跳过前台通知、是否让位渲染）一律用它——
     * 只看 [accessibilityActive] 会在"用户切回前台通道但系统里没关无障碍"时误判。
     */
    fun isAccessibilityChannel(): Boolean =
        accessibilityActive.value &&
            settings.settings.value.general.keepAliveChannel == KeepAliveChannel.ACCESSIBILITY

    /** 无障碍服务是否已连接（系统侧开启且已 onServiceConnected） */
    private val _accessibilityActive = MutableStateFlow(false)
    val accessibilityActive: StateFlow<Boolean> = _accessibilityActive.asStateFlow()

    /** 当前渲染悬浮窗的宿主的动作入口（关闭触摸穿透等），无宿主时为 null */
    @Volatile
    private var disableTouchThroughAction: (() -> Unit)? = null

    /**
     * 无障碍服务 Context：其 getSystemService(WINDOW_SERVICE) 返回**携带无障碍窗口 token**
     * 的 WindowManager——TYPE_ACCESSIBILITY_OVERLAY 必须用它 addView，
     * 普通 Service 的 WindowManager 会被 WMS 以 BadToken 拒绝。
     */
    @Volatile
    var accessibilityContext: Context? = null
        private set

    /** BleService 是否存活（决定无障碍服务销毁时是否该停掉共享的内置服务器） */
    @Volatile
    var bleServiceRunning: Boolean = false

    /** 注册宿主动作；[owner] 用于身份校验，避免通道切换时误清掉新宿主的注册 */
    fun registerTouchThroughAction(owner: Any, action: () -> Unit) {
        touchThroughOwner = owner
        disableTouchThroughAction = action
    }

    fun unregisterTouchThroughAction(owner: Any) {
        if (touchThroughOwner === owner) {
            touchThroughOwner = null
            disableTouchThroughAction = null
        }
    }

    fun invokeDisableTouchThrough() {
        disableTouchThroughAction?.invoke()
    }

    @Volatile
    private var touchThroughOwner: Any? = null

    /** 系统 UI（下拉通知面板/快捷设置）是否展开——accessibility overlay 需要临时让位 */
    private val _systemUiExpanded = MutableStateFlow(false)
    val systemUiExpanded: StateFlow<Boolean> = _systemUiExpanded.asStateFlow()

    fun setAccessibilityActive(active: Boolean, context: Context? = null) {
        accessibilityContext = if (active) context else null
        _accessibilityActive.value = active
    }

    fun setSystemUiExpanded(expanded: Boolean) {
        _systemUiExpanded.value = expanded
    }
}
