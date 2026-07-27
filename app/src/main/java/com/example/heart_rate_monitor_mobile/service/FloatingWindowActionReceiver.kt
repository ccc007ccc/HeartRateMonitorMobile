package com.example.heart_rate_monitor_mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.service.overlay.FloatingWindowHost

/**
 * 悬浮窗通知动作接收器（关闭触摸穿透）。
 *
 * 用广播而非 startService：动作要路由到"当前正在渲染悬浮窗的宿主"，
 * 而宿主可能是前台服务，也可能是无障碍服务（v2.2 双通道）。
 */
class FloatingWindowActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FloatingWindowHost.ACTION_DISABLE_TOUCH_THROUGH) return
        AppContainer.get(context).overlayCoordinator.invokeDisableTouchThrough()
    }
}
