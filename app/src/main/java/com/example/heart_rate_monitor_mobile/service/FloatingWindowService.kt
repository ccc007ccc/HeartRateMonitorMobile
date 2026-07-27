package com.example.heart_rate_monitor_mobile.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import com.example.heart_rate_monitor_mobile.service.overlay.FloatingWindowHost

/**
 * 悬浮窗服务（前台服务通道）。
 *
 * 窗口逻辑全部在共享的 [FloatingWindowHost]；本服务只负责该通道下的生命周期：
 * 保活 start、设置关闭时 stopSelf。无障碍通道下由 HeartRateAccessibilityService
 * 托管同一个 host 实现（两通道互斥，见 SPEC v2.2）。
 */
class FloatingWindowService : Service() {

    private var host: FloatingWindowHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        host = FloatingWindowHost(
            context = this,
            windowType = windowType,
            isAccessibilityHost = false,
            onHidden = { stopSelf() },
        )
    }

    /**
     * 无 action 的 start 为保活请求：使悬浮窗在 Activity 全部销毁
     * （如开启"退出应用隐藏后台"后按 HOME 触发 finishAffinity）后仍持续显示。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        host?.release()
        host = null
    }
}
