package com.example.heart_rate_monitor_mobile.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.example.heart_rate_monitor_mobile.service.overlay.HeartbeatAnimator
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * 心率悬浮窗服务。
 *
 * 重构后：数据一律来自 AppContainer.heartRate（进程级 repository），
 * 不再 bindService(BleService)；窗口显隐由 floating_window_enabled 设置流驱动，
 * 外观由 FloatingWindowSettings 设置流驱动（替代 OnSharedPreferenceChangeListener）。
 */
class FloatingWindowService : Service() {

    companion object {
        /** 通知栏动作：关闭触摸穿透 */
        const val ACTION_DISABLE_TOUCH_THROUGH = "com.example.heart_rate_monitor_mobile.DISABLE_TOUCH_THROUGH"
        private const val TAG = "FloatingWindowService"
        private const val TOUCH_THROUGH_CHANNEL_ID = "floating_touch_through"
        private const val TOUCH_THROUGH_NOTIFICATION_ID = 1001
        /** 长按触发阈值（毫秒） */
        private const val LONG_PRESS_THRESHOLD = 500L
        /** 判定为拖动的移动阈值（像素） */
        private const val TOUCH_SLOP = 10f
    }

    private val container by lazy { AppContainer.get(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 处理两类 startService 调用：
     * 1. 通知栏「关闭触摸穿透」按钮（ACTION_DISABLE_TOUCH_THROUGH）：一次性动作，处理完即
     *    stopSelf(startId) 释放本次 start 请求。
     * 2. 开启悬浮窗时的保活 start：使服务在开启"退出应用隐藏后台"后按 HOME 触发
     *    finishAffinity 时仍能存活，悬浮窗持续显示。设置关闭时 stopSelf() 释放。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE_TOUCH_THROUGH -> {
                disableTouchThrough()
                stopSelf(startId)
            }
            // 无 action：保活 start，不释放
        }
        return START_STICKY
    }

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingWindowBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var heartbeatAnimator: HeartbeatAnimator

    private var isWindowShown = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    /** 触摸穿透是否已开启：开启后悬浮窗不再接收触摸事件，触摸直接传递给下方应用 */
    private var isTouchThroughEnabled = false
    private val touchThroughHandler = Handler(Looper.getMainLooper())
    private var touchThroughRunnable: Runnable? = null
    /** 触摸穿透开启时覆盖在悬浮窗中心的不可见触摸接收窗口，用于长按关闭穿透 */
    private var touchThroughCatcherView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val contextWithTheme = ContextThemeWrapper(this, R.style.Theme_HeartRateMonitorMobile)
        binding = LayoutFloatingWindowBinding.inflate(LayoutInflater.from(contextWithTheme))
        heartbeatAnimator = HeartbeatAnimator(binding.floatingHeartIcon)

        initLayoutParams()
        setupTouchListener()
        createTouchThroughNotificationChannel()
        observeData()
        observeSettings()
    }

    private fun observeData() {
        serviceScope.launch {
            container.heartRate.heartRate.collectLatest { rate ->
                binding.floatingBpmNumber.text = if (rate > 0) "$rate" else "--"
                heartbeatAnimator.update(
                    rate,
                    container.settings.settings.value.general.heartbeatAnimationEnabled &&
                        container.heartRate.isDeviceConnected(),
                )
            }
        }
        serviceScope.launch {
            container.heartRate.speed.collectLatest { speed ->
                binding.floatingSpeedNumber.text = String.format(Locale.US, "%.1f", speed)
            }
        }
    }

    private fun observeSettings() {
        // 显隐由设置驱动。只在 true→false 跃迁时停止服务：
        // 服务刚被启动而设置写入尚未落盘时，首个收集值可能仍是 false，
        // 此时立即 stopSelf 会让悬浮窗永远出不来（写后读竞态）。
        serviceScope.launch {
            var wasEnabled: Boolean? = null
            container.settings.flowOf { it.floating.enabled }.collectLatest { enabled ->
                if (enabled) {
                    showWindow()
                } else if (wasEnabled == true) {
                    hideWindowAndStop()
                }
                wasEnabled = enabled
            }
        }
        // 外观（含速度显示开关）
        serviceScope.launch {
            container.settings.flowOf { it.floating to it.general.speedDisplayEnabled }
                .collectLatest { if (isWindowShown) updateWindowAppearance() }
        }
    }

    private fun showWindow() {
        if (isWindowShown) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            windowManager.addView(binding.root, layoutParams)
            isWindowShown = true
            updateWindowAppearance()
            // 提升为 started 服务，使悬浮窗在 Activity 全部销毁后仍能存活
            startService(Intent(this, FloatingWindowService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "添加悬浮窗失败", e)
        }
    }

    private fun hideWindowAndStop() {
        hideWindow()
        stopSelf()
    }

    private fun hideWindow() {
        if (!isWindowShown) return
        // 重置触摸穿透状态并清理通知与 catcher（避免隐藏后残留）
        if (isTouchThroughEnabled) {
            isTouchThroughEnabled = false
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            cancelTouchThroughNotification()
        }
        removeTouchThroughCatcher()
        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
        touchThroughRunnable = null
        try {
            windowManager.removeView(binding.root)
            isWindowShown = false
        } catch (e: Exception) {
            Log.w(TAG, "移除悬浮窗失败", e)
        }
    }

    private fun initLayoutParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    // 启动长按检测：500ms 内未移动超过阈值则开启触摸穿透
                    touchThroughRunnable = Runnable {
                        if (!isTouchThroughEnabled) enableTouchThrough()
                    }
                    touchThroughHandler.postDelayed(touchThroughRunnable!!, LONG_PRESS_THRESHOLD)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    // 超过移动阈值则取消长按检测（判定为拖动）
                    if (dx.absoluteValue > TOUCH_SLOP || dy.absoluteValue > TOUCH_SLOP) {
                        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
                    }
                    // 触摸穿透开启后不处理拖动
                    if (!isTouchThroughEnabled) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        if (isWindowShown) windowManager.updateViewLayout(binding.root, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
                    touchThroughRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 开启触摸穿透：为窗口添加 FLAG_NOT_TOUCHABLE，触摸事件直接传递给下方应用。
     * 同时在悬浮窗中心叠加一个不可见的触摸接收窗口（catcher），用于长按关闭穿透。
     * 通知栏按钮作为关闭的备选方式。
     */
    private fun enableTouchThrough() {
        if (isTouchThroughEnabled || !isWindowShown) return
        isTouchThroughEnabled = true
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(binding.root, layoutParams)
        } catch (e: Exception) {
            // 更新失败则回退状态
            isTouchThroughEnabled = false
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            return
        }
        addTouchThroughCatcher()
        showTouchThroughNotification()
        Toast.makeText(this, getString(R.string.floating_pass_through_on), Toast.LENGTH_LONG).show()
    }

    /**
     * 关闭触摸穿透：移除 catcher 窗口和 FLAG_NOT_TOUCHABLE，恢复拖动。
     * 可由 catcher 的长按或通知栏按钮触发。
     */
    private fun disableTouchThrough() {
        val wasEnabled = isTouchThroughEnabled
        isTouchThroughEnabled = false
        removeTouchThroughCatcher()
        if (wasEnabled && isWindowShown) {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(binding.root, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "关闭触摸穿透时更新窗口失败", e)
            }
        }
        cancelTouchThroughNotification()
        if (wasEnabled) {
            Toast.makeText(this, getString(R.string.floating_pass_through_off), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 在悬浮窗中心叠加一个不可见的触摸接收窗口。
     * 主窗口设置了 FLAG_NOT_TOUCHABLE 后无法接收触摸，
     * catcher 负责接收长按手势以关闭触摸穿透。
     * catcher 仅覆盖中心 48dp×48dp 区域，其余区域触摸直接穿透。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchThroughCatcher() {
        if (touchThroughCatcherView != null) return
        val catcherSize = dpToPx(48f)
        val catcher = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        touchThroughRunnable = Runnable { disableTouchThrough() }
                        touchThroughHandler.postDelayed(touchThroughRunnable!!, LONG_PRESS_THRESHOLD)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if ((event.rawX - initialTouchX).absoluteValue > TOUCH_SLOP ||
                            (event.rawY - initialTouchY).absoluteValue > TOUCH_SLOP
                        ) {
                            touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
                        touchThroughRunnable = null
                        true
                    }
                    else -> false
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 以悬浮窗实际位置和尺寸计算 catcher 居中坐标
        val windowWidth = binding.root.width.coerceAtLeast(catcherSize)
        val windowHeight = binding.root.height.coerceAtLeast(catcherSize)
        val catcherX = layoutParams.x + (windowWidth - catcherSize) / 2
        val catcherY = layoutParams.y + (windowHeight - catcherSize) / 2

        val catcherParams = WindowManager.LayoutParams(
            catcherSize, catcherSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = catcherX
            y = catcherY
        }

        touchThroughCatcherView = catcher
        try {
            windowManager.addView(catcher, catcherParams)
        } catch (e: Exception) {
            // 添加失败不影响穿透本身，仍可通过通知关闭
            touchThroughCatcherView = null
        }
    }

    private fun removeTouchThroughCatcher() {
        touchThroughCatcherView?.let { catcher ->
            try {
                windowManager.removeView(catcher)
            } catch (e: Exception) {
                Log.w(TAG, "移除 catcher 失败", e)
            }
        }
        touchThroughCatcherView = null
    }

    private fun createTouchThroughNotificationChannel() {
        val channel = NotificationChannel(
            TOUCH_THROUGH_CHANNEL_ID,
            getString(R.string.notif_channel_touch_through),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_touch_through_desc)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun showTouchThroughNotification() {
        val disableIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_DISABLE_TOUCH_THROUGH
        }
        val disablePendingIntent = PendingIntent.getService(
            this, 0, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, TOUCH_THROUGH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.floating_notif_title))
            .setContentText(getString(R.string.floating_notif_text))
            .addAction(
                R.drawable.ic_floating_window_on,
                getString(R.string.floating_notif_action_disable),
                disablePendingIntent,
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(TOUCH_THROUGH_NOTIFICATION_ID, notification)
    }

    private fun cancelTouchThroughNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(TOUCH_THROUGH_NOTIFICATION_ID)
    }

    private fun updateWindowAppearance() {
        val settings = container.settings.settings.value
        val floating = settings.floating

        val bgAlpha = floating.backgroundAlphaPercent / 100f
        val borderAlpha = floating.borderAlphaPercent / 100f
        val finalBgColor = Color.argb(
            (255 * bgAlpha).roundToInt(),
            Color.red(floating.backgroundColor),
            Color.green(floating.backgroundColor),
            Color.blue(floating.backgroundColor),
        )
        val finalBorderColor = Color.argb(
            (255 * borderAlpha).roundToInt(),
            Color.red(floating.borderColor),
            Color.green(floating.borderColor),
            Color.blue(floating.borderColor),
        )
        val scaleFactor = floating.sizePercent / 100f
        val iconScaleFactor = floating.iconSizePercent / 100f
        val baseIconSizeDp = 18f
        val baseTextSizeSp = 16f
        val baseSmallTextSizeSp = 12f
        val basePaddingDp = 8f
        val baseMarginDp = 4f

        val isSpeedEnabled = settings.general.speedDisplayEnabled
        binding.floatingSpeedLayout.visibility = if (isSpeedEnabled) View.VISIBLE else View.GONE
        binding.floatingSpeedDivider.visibility = if (isSpeedEnabled) View.VISIBLE else View.GONE

        binding.floatingBpmText.visibility = if (floating.bpmTextEnabled) View.VISIBLE else View.GONE
        binding.floatingHeartIcon.visibility = if (floating.heartIconEnabled) View.VISIBLE else View.GONE

        val iconSizePx = dpToPx(baseIconSizeDp * iconScaleFactor)
        binding.floatingHeartIcon.layoutParams = binding.floatingHeartIcon.layoutParams.apply {
            width = iconSizePx
            height = iconSizePx
        }
        binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)
        binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSmallTextSizeSp * scaleFactor)

        binding.floatingSpeedNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)
        binding.floatingSpeedUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSmallTextSizeSp * scaleFactor)
        binding.floatingSpeedNumber.setTextColor(floating.textColor)
        binding.floatingSpeedUnit.setTextColor(floating.textColor)
        binding.floatingSpeedDivider.setTextColor(floating.textColor)

        binding.floatingHeartIcon.setColorFilter(floating.textColor)
        binding.floatingBpmNumber.setTextColor(floating.textColor)
        binding.floatingBpmText.setTextColor(floating.textColor)

        val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams
        bpmNumberParams.marginStart =
            if (floating.heartIconEnabled) dpToPx(baseMarginDp * scaleFactor) else 0
        binding.floatingBpmNumber.layoutParams = bpmNumberParams

        val rootLayout = binding.root.getChildAt(0) as LinearLayout
        val paddingPx = dpToPx(basePaddingDp * scaleFactor)
        rootLayout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        (binding.root as MaterialCardView).apply {
            setCardBackgroundColor(finalBgColor)
            radius = floating.cornerRadius.toFloat()
            setStrokeColor(finalBorderColor)
            strokeWidth = dpToPx(1f)
        }
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        heartbeatAnimator.stop()
        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
        cancelTouchThroughNotification()
        serviceScope.cancel()
    }
}
