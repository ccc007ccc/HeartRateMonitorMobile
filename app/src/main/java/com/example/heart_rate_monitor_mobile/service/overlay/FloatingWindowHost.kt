package com.example.heart_rate_monitor_mobile.service.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
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
import com.example.heart_rate_monitor_mobile.data.settings.KeepAliveChannel
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * 心率悬浮窗宿主：窗口创建/显隐/外观/拖动/触摸穿透的唯一实现。
 *
 * 双通道复用（v2.2）：由 [windowType] 注入窗口层级——
 * - 前台服务通道：TYPE_APPLICATION_OVERLAY（需悬浮窗权限）
 * - 无障碍通道：TYPE_ACCESSIBILITY_OVERLAY（免权限、层级更高、可覆盖游戏沉浸界面）
 *
 * 宿主自身不管理进程保活与生命周期，由持有它的 Service 负责。
 */
class FloatingWindowHost(
    private val context: Context,
    private val windowType: Int,
    /** 本宿主是否属于无障碍通道（决定与另一通道的互斥判断） */
    private val isAccessibilityHost: Boolean,
    /** 悬浮窗被隐藏（设置关闭 / 通道让位）时回调，供宿主 Service 决定是否 stopSelf */
    private val onHidden: () -> Unit = {},
) {
    private val container = AppContainer.get(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val binding: LayoutFloatingWindowBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val heartbeatAnimator: HeartbeatAnimator

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

    init {
        val contextWithTheme = ContextThemeWrapper(context, R.style.Theme_HeartRateMonitorMobile)
        binding = LayoutFloatingWindowBinding.inflate(LayoutInflater.from(contextWithTheme))
        heartbeatAnimator = HeartbeatAnimator(binding.floatingHeartIcon)
        initLayoutParams()
        setupTouchListener()
        createTouchThroughNotificationChannel()
        observeData()
        observeSettings()
        container.overlayCoordinator.registerTouchThroughAction(this) { disableTouchThrough() }
    }

    /** 宿主销毁：释放窗口与协程 */
    fun release() {
        hideWindow()
        heartbeatAnimator.stop()
        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
        cancelTouchThroughNotification()
        scope.cancel()
        container.overlayCoordinator.unregisterTouchThroughAction(this)
    }

    fun onDisableTouchThroughAction() = disableTouchThrough()

    private fun observeData() {
        scope.launch {
            container.heartRate.heartRate.collectLatest { rate ->
                binding.floatingBpmNumber.text = if (rate > 0) "$rate" else "--"
                heartbeatAnimator.update(
                    rate,
                    container.settings.settings.value.general.heartbeatAnimationEnabled &&
                        container.heartRate.isDeviceConnected(),
                )
            }
        }
        scope.launch {
            container.heartRate.speed.collectLatest { speed ->
                binding.floatingSpeedNumber.text = String.format(Locale.US, "%.1f", speed)
            }
        }
    }

    private fun observeSettings() {
        // 显隐 = 悬浮窗开关 AND 本宿主是当前生效通道。
        // 通道互斥：无障碍生效时只由无障碍宿主渲染，前台宿主让位（并 onHidden 让服务自停），
        // 避免两条通道同时 addView 造成双窗叠加。
        scope.launch {
            var wasVisible: Boolean? = null
            combine(
                container.settings.flowOf { it.floating.enabled },
                container.overlayCoordinator.accessibilityActive,
                container.settings.flowOf { it.general.keepAliveChannel },
            ) { enabled, accessibilityActive, channel ->
                val accessibilityOwns =
                    accessibilityActive && channel == KeepAliveChannel.ACCESSIBILITY
                enabled && (isAccessibilityHost == accessibilityOwns)
            }.collectLatest { shouldShow ->
                if (shouldShow) {
                    showWindow()
                } else {
                    hideWindow()
                    if (wasVisible == true) onHidden()
                }
                wasVisible = shouldShow
            }
        }
        // 外观（含速度显示开关）
        scope.launch {
            container.settings.flowOf { it.floating to it.general.speedDisplayEnabled }
                .collectLatest { if (isWindowShown) updateWindowAppearance() }
        }
    }

    fun showWindow() {
        if (isWindowShown) return
        // 无障碍通道的窗口类型不需要悬浮窗权限
        if (windowType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
            !Settings.canDrawOverlays(context)
        ) return
        try {
            windowManager.addView(binding.root, layoutParams)
            isWindowShown = true
            updateWindowAppearance()
        } catch (e: Exception) {
            Log.w(TAG, "添加悬浮窗失败", e)
        }
    }

    fun hideWindow() {
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
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
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
        Toast.makeText(context, context.getString(R.string.floating_pass_through_on), Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, context.getString(R.string.floating_pass_through_off), Toast.LENGTH_SHORT).show()
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
        val catcher = View(context).apply {
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

        // 以悬浮窗实际位置和尺寸计算 catcher 居中坐标
        val windowWidth = binding.root.width.coerceAtLeast(catcherSize)
        val windowHeight = binding.root.height.coerceAtLeast(catcherSize)
        val catcherX = layoutParams.x + (windowWidth - catcherSize) / 2
        val catcherY = layoutParams.y + (windowHeight - catcherSize) / 2

        val catcherParams = WindowManager.LayoutParams(
            catcherSize, catcherSize,
            windowType,
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
            context.getString(R.string.notif_channel_touch_through),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_touch_through_desc)
            setShowBadge(false)
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun showTouchThroughNotification() {
        val disableIntent = Intent(context, com.example.heart_rate_monitor_mobile.service.FloatingWindowActionReceiver::class.java).apply {
            action = ACTION_DISABLE_TOUCH_THROUGH
        }
        val disablePendingIntent = PendingIntent.getBroadcast(
            context, 0, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, TOUCH_THROUGH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(context.getString(R.string.floating_notif_title))
            .setContentText(context.getString(R.string.floating_notif_text))
            .addAction(
                R.drawable.ic_floating_window_on,
                context.getString(R.string.floating_notif_action_disable),
                disablePendingIntent,
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(TOUCH_THROUGH_NOTIFICATION_ID, notification)
    }

    private fun cancelTouchThroughNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(TOUCH_THROUGH_NOTIFICATION_ID)
    }

    fun updateWindowAppearance() {
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
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()


    companion object {
        private const val TAG = "FloatingWindowHost"
        const val ACTION_DISABLE_TOUCH_THROUGH = "com.example.heart_rate_monitor_mobile.DISABLE_TOUCH_THROUGH"
        private const val TOUCH_THROUGH_CHANNEL_ID = "floating_touch_through"
        private const val TOUCH_THROUGH_NOTIFICATION_ID = 1001
        /** 长按触发阈值（毫秒） */
        private const val LONG_PRESS_THRESHOLD = 500L
        /** 判定为拖动的移动阈值（像素） */
        private const val TOUCH_SLOP = 10f
    }
}
