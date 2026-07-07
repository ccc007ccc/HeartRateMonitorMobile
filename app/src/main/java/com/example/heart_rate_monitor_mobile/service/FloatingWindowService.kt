package com.example.heart_rate_monitor_mobile.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import com.juul.kable.State
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    companion object {
        /** 通知栏动作：关闭触摸穿透 */
        const val ACTION_DISABLE_TOUCH_THROUGH = "com.example.heart_rate_monitor_mobile.DISABLE_TOUCH_THROUGH"
        private const val TOUCH_THROUGH_CHANNEL_ID = "floating_touch_through"
        private const val TOUCH_THROUGH_NOTIFICATION_ID = 1001
        /** 长按触发阈值（毫秒） */
        private const val LONG_PRESS_THRESHOLD = 500L
        /** 判定为拖动的移动阈值（像素） */
        private const val TOUCH_SLOP = 10f
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): FloatingWindowService = this@FloatingWindowService }
    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 处理两类 startService 调用：
     * 1. 通知栏「关闭触摸穿透」按钮（ACTION_DISABLE_TOUCH_THROUGH）：一次性动作，处理完即
     *    stopSelf(startId) 释放本次 start 请求；若 Activity 仍绑定本服务则服务不会被销毁。
     * 2. showWindow() 中的无 action 保活 start：使服务在 Activity 解绑（如开启"退出应用隐藏
     *    后台"后按 HOME 触发 finishAffinity）后仍能存活，悬浮窗持续显示。hideWindow() 时
     *    stopSelf() 释放该保活 start。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE_TOUCH_THROUGH -> {
                disableTouchThrough()
                stopSelf(startId)
            }
            // 无 action：showWindow 保活 start，不释放
        }
        return START_STICKY
    }

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingWindowBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var sharedPreferences: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bleService: BleService? = null
    private var isServiceBound = false

    private var isWindowShown = false
    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f

    /** 触摸穿透是否已开启：开启后悬浮窗不再接收触摸事件，触摸直接传递给下方应用 */
    private var isTouchThroughEnabled = false
    private val touchThroughHandler = Handler(Looper.getMainLooper())
    private var touchThroughRunnable: Runnable? = null
    /** 触摸穿透开启时覆盖在悬浮窗中心的不可见触摸接收窗口，用于长按关闭穿透 */
    private var touchThroughCatcherView: View? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isServiceBound = true
            observeBleData()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isServiceBound = false
        }
    }

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (isWindowShown) updateWindowAppearance()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val contextWithTheme = ContextThemeWrapper(this, R.style.Theme_HeartRateMonitorMobile)
        binding = LayoutFloatingWindowBinding.inflate(LayoutInflater.from(contextWithTheme))

        initLayoutParams()
        setupTouchListener()
        createTouchThroughNotificationChannel()
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)

        // Bind to BleService to get data
        Intent(this, BleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeBleData() {
        serviceScope.launch {
            bleService?.heartRate?.collectLatest { rate ->
                updateHeartRateText(rate)
                updateHeartbeatAnimation(rate)
            }
        }
        // 监听速度数据
        serviceScope.launch {
            bleService?.speed?.collectLatest { speed ->
                updateSpeedText(speed)
            }
        }
    }

    fun showWindow() {
        if (isWindowShown) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        try {
            windowManager.addView(binding.root, layoutParams)
            isWindowShown = true
            updateWindowAppearance()
            // 提升为 started 服务，使悬浮窗在 Activity 解绑（如开启"退出应用隐藏后台"后按 HOME 触发 finishAffinity）后仍能存活
            startService(Intent(this, FloatingWindowService::class.java))
        } catch (e: Exception) {
            // Handle exception
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
        // 取消可能挂起的长按回调
        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
        touchThroughRunnable = null
        try {
            windowManager.removeView(binding.root)
            isWindowShown = false
            // 释放 showWindow 时的 start 保活；若仍被绑定则服务继续存活
            stopSelf()
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun updateHeartRateText(rate: Int) {
        binding.floatingBpmNumber.text = if (rate > 0) "$rate" else "--"
    }

    private fun updateSpeedText(speed: Float) {
        binding.floatingSpeedNumber.text = String.format("%.1f", speed)
    }

    private fun updateHeartbeatAnimation(bpm: Int) {
        val heartIcon = binding.floatingHeartIcon
        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)
        val isConnected = bleService?.isDeviceConnected() ?: false

        if (isAnimationEnabled && bpm > 30 && isConnected) {
            val targetDuration = (60000f / bpm).toLong()
            if (heartRateAnimator == null || (currentDuration - targetDuration).absoluteValue > 50) {
                currentDuration = targetDuration
                heartRateAnimator?.cancel()
                heartRateAnimator = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply {
                    duration = currentDuration
                    interpolator = beatInterpolator
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener { animation ->
                        val scale = animation.animatedValue as Float
                        heartIcon.scaleX = scale
                        heartIcon.scaleY = scale
                    }
                    start()
                }
            }
        } else {
            heartRateAnimator?.cancel()
            heartRateAnimator = null
            currentDuration = 0L
            heartIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun initLayoutParams() { val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE; layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 } }
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
        Toast.makeText(this, "触摸穿透已开启，长按悬浮窗或点击通知关闭", Toast.LENGTH_LONG).show()
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
                // 忽略
            }
        }
        cancelTouchThroughNotification()
        if (wasEnabled) {
            Toast.makeText(this, "触摸穿透已关闭，可拖动悬浮窗", Toast.LENGTH_SHORT).show()
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
                            (event.rawY - initialTouchY).absoluteValue > TOUCH_SLOP) {
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

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        // 以悬浮窗实际位置和尺寸计算 catcher 居中坐标
        val windowWidth = binding.root.width.coerceAtLeast(catcherSize)
        val windowHeight = binding.root.height.coerceAtLeast(catcherSize)
        val catcherX = layoutParams.x + (windowWidth - catcherSize) / 2
        val catcherY = layoutParams.y + (windowHeight - catcherSize) / 2

        val catcherParams = WindowManager.LayoutParams(
            catcherSize, catcherSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
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
                // 忽略
            }
        }
        touchThroughCatcherView = null
    }

    private fun createTouchThroughNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TOUCH_THROUGH_CHANNEL_ID,
                "悬浮窗触摸穿透",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗触摸穿透状态提醒"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showTouchThroughNotification() {
        val disableIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_DISABLE_TOUCH_THROUGH
        }
        val disablePendingIntent = PendingIntent.getService(
            this, 0, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TOUCH_THROUGH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("触摸穿透已开启")
            .setContentText("长按悬浮窗或点击下方按钮关闭")
            .addAction(R.drawable.ic_floating_window_on, "关闭触摸穿透", disablePendingIntent)
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
        val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK)
        val bgColor = sharedPreferences.getInt("floating_bg_color", Color.BLACK)
        val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY)
        val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 10) / 100f
        val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f
        val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 100).toFloat()
        val sizePercent = sharedPreferences.getInt("floating_size", 100)
        val iconSizePercent = sharedPreferences.getInt("floating_icon_size", 100)
        val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true)
        val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true)
        val isSpeedEnabled = sharedPreferences.getBoolean("speed_display_enabled", false) // 检查是否开启时速显示

        val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor))
        val scaleFactor = sizePercent / 100f
        val iconScaleFactor = iconSizePercent / 100f
        val baseIconSizeSp = 18f
        val baseTextSizeSp = 16f
        val baseSmallTextSizeSp = 12f
        val basePaddingDp = 8f
        val baseMarginDp = 4f

        // 控制时速显示部分的可见性
        binding.floatingSpeedLayout.visibility = if (isSpeedEnabled) View.VISIBLE else View.GONE
        binding.floatingSpeedDivider.visibility = if (isSpeedEnabled) View.VISIBLE else View.GONE

        binding.floatingBpmText.visibility = if (isBpmTextEnabled) View.VISIBLE else View.GONE
        binding.floatingHeartIcon.visibility = if (isHeartIconEnabled) View.VISIBLE else View.GONE

        binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * iconScaleFactor)
        binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)
        binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSmallTextSizeSp * scaleFactor)

        // 设置速度文字大小和颜色
        binding.floatingSpeedNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)
        binding.floatingSpeedUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSmallTextSizeSp * scaleFactor)
        binding.floatingSpeedNumber.setTextColor(textColor)
        binding.floatingSpeedUnit.setTextColor(textColor)
        binding.floatingSpeedDivider.setTextColor(textColor)

        binding.floatingHeartIcon.setTextColor(textColor)
        binding.floatingBpmNumber.setTextColor(textColor)
        binding.floatingBpmText.setTextColor(textColor)

        val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams
        bpmNumberParams.marginStart = if (isHeartIconEnabled) dpToPx(baseMarginDp * scaleFactor) else 0
        binding.floatingBpmNumber.layoutParams = bpmNumberParams

        val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout
        val paddingPx = dpToPx(basePaddingDp * scaleFactor)
        rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        (binding.root as MaterialCardView).apply {
            setCardBackgroundColor(finalBgColor)
            radius = cornerRadius
            setStrokeColor(finalBorderColor)
            strokeWidth = dpToPx(1f)
        }
    }
    private fun dpToPx(dp: Float): Int { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt() }

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        touchThroughRunnable?.let { touchThroughHandler.removeCallbacks(it) }
        cancelTouchThroughNotification()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        serviceScope.cancel()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}