package com.example.heart_rate_monitor_mobile

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingWindowBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var sharedPreferences: SharedPreferences

    // --- 拖动相关变量 ---
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // --- 动画相关变量 ---
    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()


    companion object {
        const val ACTION_UPDATE_HEART_RATE = "com.example.heart_rate_monitor_mobile.UPDATE_HEART_RATE"
        const val EXTRA_HEART_RATE = "extra_heart_rate"
    }

    // 广播接收器，用于接收来自 MainActivity 的心率数据
    private val heartRateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_HEART_RATE) {
                val rate = intent.getIntExtra(EXTRA_HEART_RATE, 0)
                updateHeartRateText(rate)
                updateHeartbeatAnimation(rate)
            }
        }
    }

    // 监听 SharedPreferences 的变化，以便实时更新悬浮窗样式
    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateWindowAppearance()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val contextWithTheme = ContextThemeWrapper(this, R.style.Theme_HeartRateMonitorMobile)
        val inflater = LayoutInflater.from(contextWithTheme)

        binding = LayoutFloatingWindowBinding.inflate(inflater)

        initLayoutParams()
        setupTouchListener()

        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        LocalBroadcastManager.getInstance(this).registerReceiver(heartRateReceiver, IntentFilter(ACTION_UPDATE_HEART_RATE))

        windowManager.addView(binding.root, layoutParams)
        updateWindowAppearance()
    }

    /**
     * 初始化 WindowManager.LayoutParams
     */
    private fun initLayoutParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            // 关键：将宽高设置为自适应内容，让窗口大小由内部内容决定
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
    }

    /**
     * 设置悬浮窗的触摸监听，用于拖动
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(binding.root, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 根据 SharedPreferences 中的设置更新悬浮窗外观
     */
    private fun updateWindowAppearance() {
        // --- 颜色和透明度 ---
        val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK)
        val bgColor = sharedPreferences.getInt("floating_bg_color", Color.WHITE)
        val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY)
        val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 100) / 100f
        val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f

        val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor))

        // --- 尺寸和圆角 ---
        val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 16).toFloat()
        // 从设置中获取统一的大小值 (50-200)
        val sizePercent = sharedPreferences.getInt("floating_size", 100)
        // 将百分比转换为一个缩放因子 (例如，100 -> 1.0f, 150 -> 1.5f)
        val scaleFactor = sizePercent / 100f

        // --- 关键逻辑：根据缩放因子调整内部元素尺寸 ---
        // 定义基础尺寸
        val baseIconSizeSp = 24f
        val baseTextSizeSp = 16f
        val basePaddingDp = 8f
        val baseMarginDp = 4f

        // 应用缩放
        binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * scaleFactor)
        binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)

        // 获取 TextView 的 LayoutParams 并设置边距
        val bpmTextParams = binding.floatingBpmText.layoutParams as LinearLayout.LayoutParams
        bpmTextParams.marginStart = dpToPx(baseMarginDp * scaleFactor)
        binding.floatingBpmText.layoutParams = bpmTextParams

        // 获取根布局（LinearLayout）并设置内边距
        val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout
        val paddingPx = dpToPx(basePaddingDp * scaleFactor)
        rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)


        // --- 应用其他样式 ---
        binding.floatingBpmText.setTextColor(textColor)
        binding.floatingHeartIcon.setTextColor(textColor)

        (binding.root as MaterialCardView).apply {
            setCardBackgroundColor(finalBgColor)
            radius = cornerRadius // 圆角保持绝对值，不随缩放变化
            setStrokeColor(finalBorderColor)
            strokeWidth = dpToPx(1f) // 边框宽度固定为1dp
        }
    }

    /**
     * 更新心率文本
     */
    private fun updateHeartRateText(rate: Int) {
        val text = if (rate > 0) "$rate bpm" else "-- bpm"
        binding.floatingBpmText.text = text
    }

    /**
     * 更新心跳动画
     */
    private fun updateHeartbeatAnimation(bpm: Int) {
        val heartIcon = binding.floatingHeartIcon
        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)

        if (isAnimationEnabled && bpm > 30) {
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

    /**
     * 辅助函数：将 DP 单位转换为 PX 单位
     */
    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartRateAnimator?.cancel()
        if (binding.root.isAttachedToWindow) {
            windowManager.removeView(binding.root)
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(heartRateReceiver)
    }
}