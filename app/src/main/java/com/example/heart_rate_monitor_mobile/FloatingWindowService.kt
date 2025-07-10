package com.example.heart_rate_monitor_mobile

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
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

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()

    companion object {
        const val ACTION_UPDATE_HEART_RATE = "com.example.heart_rate_monitor_mobile.UPDATE_HEART_RATE"
        const val EXTRA_HEART_RATE = "extra_heart_rate"
    }

    private val heartRateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_HEART_RATE) {
                val rate = intent.getIntExtra(EXTRA_HEART_RATE, 0)
                updateHeartRateText(rate)
                updateHeartbeatAnimation(rate)
            }
        }
    }

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

    private fun initLayoutParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
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

    private fun updateWindowAppearance() {
        // --- 获取设置 ---
        val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK)
        val bgColor = sharedPreferences.getInt("floating_bg_color", Color.WHITE)
        val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY)
        val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 100) / 100f
        val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f
        val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 16).toFloat()
        val sizePercent = sharedPreferences.getInt("floating_size", 100)
        val iconSizePercent = sharedPreferences.getInt("floating_icon_size", 100)
        val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true)
        val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true)

        // --- 计算最终值 ---
        val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor))
        val scaleFactor = sizePercent / 100f
        val iconScaleFactor = iconSizePercent / 100f

        val baseIconSizeSp = 18f // 基础图标大小
        val baseTextSizeSp = 16f // 基础文本大小
        val baseBpmTextSizeSp = 12f // 基础BPM文本大小
        val basePaddingDp = 8f
        val baseMarginDp = 4f

        // --- 应用设置 ---
        // 文本和图标的显示/隐藏
        binding.floatingBpmText.visibility = if (isBpmTextEnabled) View.VISIBLE else View.GONE
        binding.floatingHeartIcon.visibility = if (isHeartIconEnabled) View.VISIBLE else View.GONE

        // 字体和图标大小
        binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * iconScaleFactor)
        binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor)
        binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseBpmTextSizeSp * scaleFactor)

        // 颜色
        binding.floatingHeartIcon.setTextColor(textColor)
        binding.floatingBpmNumber.setTextColor(textColor)
        binding.floatingBpmText.setTextColor(textColor)

        // --- **修复代码** ---
        // 根据心率图标的可见性，动态调整心率数字的左边距
        val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams
        if (isHeartIconEnabled) {
            bpmNumberParams.marginStart = dpToPx(baseMarginDp * scaleFactor)
        } else {
            bpmNumberParams.marginStart = 0 // 当图标隐藏时，移除左边距以使其居中
        }
        binding.floatingBpmNumber.layoutParams = bpmNumberParams
        // --- **修复结束** ---

        val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout
        val paddingPx = dpToPx(basePaddingDp * scaleFactor)
        rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        // CardView 样式
        (binding.root as MaterialCardView).apply {
            setCardBackgroundColor(finalBgColor)
            radius = cornerRadius
            setStrokeColor(finalBorderColor)
            strokeWidth = dpToPx(1f)
        }
    }


    private fun updateHeartRateText(rate: Int) {
        val text = if (rate > 0) "$rate" else "--"
        binding.floatingBpmNumber.text = text
    }

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