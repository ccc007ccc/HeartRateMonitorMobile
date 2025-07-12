package com.example.heart_rate_monitor_mobile.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import com.juul.kable.State
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): FloatingWindowService = this@FloatingWindowService }
    override fun onBind(intent: Intent?): IBinder = binder

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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isServiceBound = true
            observeBleState()
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
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)

        // Bind to BleService to get data
        Intent(this, BleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeBleState() {
        serviceScope.launch {
            bleService?.heartRate?.collectLatest { rate ->
                updateHeartRateText(rate)
                updateHeartbeatAnimation(rate)
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
        } catch (e: Exception) {
            // Handle exception
        }
    }

    fun hideWindow() {
        if (!isWindowShown) return
        try {
            windowManager.removeView(binding.root)
            isWindowShown = false
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun updateHeartRateText(rate: Int) {
        binding.floatingBpmNumber.text = if (rate > 0) "$rate" else "--"
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

    // ... [ The rest of the FloatingWindow UI methods remain the same ]
    // initLayoutParams(), setupTouchListener(), updateWindowAppearance(), dpToPx()

    private fun initLayoutParams() { val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE; layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 } }
    @SuppressLint("ClickableViewAccessibility") private fun setupTouchListener() { binding.root.setOnTouchListener { _, event -> when (event.action) { MotionEvent.ACTION_DOWN -> { initialX = layoutParams.x; initialY = layoutParams.y; initialTouchX = event.rawX; initialTouchY = event.rawY; true } MotionEvent.ACTION_MOVE -> { layoutParams.x = initialX + (event.rawX - initialTouchX).toInt(); layoutParams.y = initialY + (event.rawY - initialTouchY).toInt(); if (isWindowShown) windowManager.updateViewLayout(binding.root, layoutParams); true } else -> false } } }
    private fun updateWindowAppearance() {
        val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK)
        val bgColor = sharedPreferences.getInt("floating_bg_color", Color.BLACK)
        val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY)
        val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 10) / 100f
        val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f
        val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 100).toFloat()
        val sizePercent = sharedPreferences.getInt("floating_size", 100)
        val iconSizePercent = sharedPreferences.getInt("floating_icon_size", 100)
        val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true);
        val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true);
        val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor));
        val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)); val scaleFactor = sizePercent / 100f;
        val iconScaleFactor = iconSizePercent / 100f; val baseIconSizeSp = 18f; val baseTextSizeSp = 16f;
        val baseBpmTextSizeSp = 12f; val basePaddingDp = 8f; val baseMarginDp = 4f;
        binding.floatingBpmText.visibility = if (isBpmTextEnabled) View.VISIBLE else View.GONE;
        binding.floatingHeartIcon.visibility = if (isHeartIconEnabled) View.VISIBLE else View.GONE;
        binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * iconScaleFactor);
        binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor); binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseBpmTextSizeSp * scaleFactor); binding.floatingHeartIcon.setTextColor(textColor); binding.floatingBpmNumber.setTextColor(textColor); binding.floatingBpmText.setTextColor(textColor); val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams; bpmNumberParams.marginStart = if (isHeartIconEnabled) dpToPx(baseMarginDp * scaleFactor) else 0; binding.floatingBpmNumber.layoutParams = bpmNumberParams; val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout; val paddingPx = dpToPx(basePaddingDp * scaleFactor); rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx); (binding.root as MaterialCardView).apply { setCardBackgroundColor(finalBgColor); radius = cornerRadius; setStrokeColor(finalBorderColor); strokeWidth = dpToPx(1f) } }
    private fun dpToPx(dp: Float): Int { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt() }

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        serviceScope.cancel()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}