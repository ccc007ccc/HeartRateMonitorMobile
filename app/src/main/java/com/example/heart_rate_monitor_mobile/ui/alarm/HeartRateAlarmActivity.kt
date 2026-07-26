package com.example.heart_rate_monitor_mobile.ui.alarm

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivityHeartRateAlarmBinding
import com.example.heart_rate_monitor_mobile.service.HeartRateAlarmService
import com.example.heart_rate_monitor_mobile.service.posture.PostureCalibration
import com.example.heart_rate_monitor_mobile.service.posture.PostureDetector
import com.example.heart_rate_monitor_mobile.service.posture.PostureFeatures
import com.example.heart_rate_monitor_mobile.service.posture.PostureType
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 心率预警二级页面。
 *
 * 三段式布局：
 * 1. 姿态展示卡片（纯姿态检测，人形 emoji 随实时姿态切换，无心率/蓝牙信息）
 * 2. 姿态校准区（10 秒采样静坐/站立特征）
 * 3. 预警设置区（开关 + 超过/低于范围滑块 + 持续秒数滑块 + 重复报警）
 *
 * 数据流：
 * - 自有加速度传感器 + PostureDetector → 实时姿态展示 + 校准采样
 * - SharedPreferences 存储预警配置和校准数据
 * - 心率/蓝牙逻辑由 HeartRateAlarmService 独立处理，Activity 不涉及
 */
class HeartRateAlarmActivity : BaseActivity() {

    private lateinit var binding: ActivityHeartRateAlarmBinding
    private val settings get() = container.settings
    private val current get() = settings.settings.value
    private lateinit var sensorManager: SensorManager
    private lateinit var postureDetector: PostureDetector

    // 运动弹跳动画
    private var exerciseBounceAnimator: ValueAnimator? = null

    // 姿态显示状态
    private var currentPostureEmoji = "❓"

    // 校准状态
    private var isCalibrating = false
    private val calibrationBuffer = mutableListOf<FloatArray>()
    private var calibrationDialog: android.app.Dialog? = null
    private val calibrationHandler = Handler(Looper.getMainLooper())

    // 姿态分类循环
    private val classifyHandler = Handler(Looper.getMainLooper())
    private val classifyRunnable = object : Runnable {
        override fun run() {
            updatePostureUi(postureDetector.classify())
            classifyHandler.postDelayed(this, CLASSIFY_INTERVAL_MS)
        }
    }

    // ========== 加速度传感器 ==========

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            postureDetector.onSensorSample(event.values[0], event.values[1], event.values[2])
            if (isCalibrating) {
                calibrationBuffer.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ========== 生命周期 ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHeartRateAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        postureDetector = PostureDetector()
        postureDetector.setCalibration(
            PostureCalibration.fromJson(current.alarm.postureCalibrationJson)
        )

        setupToolbar()
        setupSwitchTint()
        setupAlarmSettings()
        observeAlarmOptionsVisibility()
        setupCalibrationButtons()
        refreshCalibrationStatus()
    }

    override fun onResume() {
        super.onResume()
        registerAccelerometer()
        classifyHandler.post(classifyRunnable)
        refreshCalibrationStatus()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorListener)
        classifyHandler.removeCallbacks(classifyRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCalibration()
        exerciseBounceAnimator?.cancel()
        exerciseBounceAnimator = null
    }

    // ========== Toolbar ==========

    /** 预警开关关闭时隐藏阈值/时长/重复等调节项（设置流驱动） */
    private fun observeAlarmOptionsVisibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.flowOf { it.alarm.enabled }.collect { enabled ->
                    binding.alarmOptionsContainer.visibility =
                        if (enabled) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.alarm_title)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * 统一开关着色样式（复刻 SettingsActivity 的 tint 逻辑）。
     * 修复 MaterialSwitch 默认关闭态与设置页其他开关不一致的问题。
     */
    private fun setupSwitchTint() {
        val activeColor = MaterialColors.getColor(
            this,
            android.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.primary_light)
        )
        val inactiveTrackColor = Color.parseColor("#E0E0E0")
        val inactiveThumbColor = Color.WHITE

        val thumbStates = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(inactiveThumbColor, activeColor)
        )

        val trackStates = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(inactiveTrackColor, ColorUtils.setAlphaComponent(activeColor, 128))
        )

        binding.alarmEnabledSwitch.thumbTintList = thumbStates
        binding.alarmEnabledSwitch.trackTintList = trackStates
        binding.repeatAlarmSwitch.thumbTintList = thumbStates
        binding.repeatAlarmSwitch.trackTintList = trackStates
    }

    // ========== 姿态 UI 更新（人形 emoji + 动画） ==========

    private fun registerAccelerometer() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun updatePostureUi(posture: PostureType) {
        if (posture.emoji == currentPostureEmoji) return
        currentPostureEmoji = posture.emoji

        // 取消已有动画
        exerciseBounceAnimator?.cancel()
        exerciseBounceAnimator = null
        binding.postureEmoji.animate().cancel()
        binding.postureEmoji.translationY = 0f

        binding.postureEmoji.text = posture.emoji
        binding.postureLabel.text = getString(posture.labelRes)

        // 更新姿态指示器高亮
        binding.indicatorSitting.alpha = if (posture == PostureType.SITTING) 1f else 0.3f
        binding.indicatorStanding.alpha = if (posture == PostureType.STANDING) 1f else 0.3f
        binding.indicatorExercise.alpha = if (posture == PostureType.EXERCISE) 1f else 0.3f

        // 弹出动画
        binding.postureEmoji.scaleX = 0.7f
        binding.postureEmoji.scaleY = 0.7f
        binding.postureEmoji.animate().scaleX(1f).scaleY(1f).setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()

        // 运动姿态：弹出后开始持续弹跳
        if (posture == PostureType.EXERCISE) {
            binding.postureEmoji.postDelayed({
                if (currentPostureEmoji == PostureType.EXERCISE.emoji) {
                    exerciseBounceAnimator = ValueAnimator.ofFloat(0f, -20f).apply {
                        duration = 400
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { animation ->
                            binding.postureEmoji.translationY = animation.animatedValue as Float
                        }
                        start()
                    }
                }
            }, 250)
        }
    }

    // ========== 姿态校准 ==========

    private fun setupCalibrationButtons() {
        binding.calibrateSittingButton.setOnClickListener { startCalibration(isSitting = true) }
        binding.calibrateStandingButton.setOnClickListener { startCalibration(isSitting = false) }
        binding.clearCalibrationButton.setOnClickListener { confirmClearCalibration() }
    }

    /** 清除校准数据（需二次确认，避免误触丢失多样本） */
    private fun confirmClearCalibration() {
        val cal = PostureCalibration.fromJson(current.alarm.postureCalibrationJson)
        if (cal == null || !cal.isComplete()) {
            Toast.makeText(this, R.string.alarm_no_calibration_data, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alarm_clear_calibration)
            .setMessage(R.string.alarm_clear_calibration_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.alarm_clear) { _, _ ->
                settings.removeAsync(SettingsKeys.POSTURE_CALIBRATION_DATA)
                postureDetector.setCalibration(null)
                refreshCalibrationStatus()
                Toast.makeText(this, R.string.alarm_calibration_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startCalibration(isSitting: Boolean) {
        val postureName =
            getString(if (isSitting) R.string.posture_sitting else R.string.posture_standing)
        calibrationBuffer.clear()
        isCalibrating = true

        // 构建对话框内容
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = CALIBRATION_DURATION_SECONDS
            progress = 0
        }
        val messageView = TextView(this).apply {
            text = getString(R.string.alarm_keep_posture, postureName, CALIBRATION_DURATION_SECONDS)
            setPadding(0, 16, 0, 0)
        }
        container.addView(progressBar)
        container.addView(messageView)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.alarm_calibrate_title, postureName))
            .setView(container)
            .setCancelable(false)
            .create()
        calibrationDialog = dialog
        dialog.show()

        // 每秒更新进度
        var elapsed = 0
        val updateRunnable = object : Runnable {
            override fun run() {
                elapsed++
                progressBar.progress = elapsed
                val remaining = CALIBRATION_DURATION_SECONDS - elapsed
                if (remaining > 0) {
                    messageView.text = getString(R.string.alarm_keep_posture, postureName, remaining)
                    calibrationHandler.postDelayed(this, 1000L)
                } else {
                    isCalibrating = false
                    dialog.dismiss()
                    calibrationDialog = null
                    computeAndSaveCalibration(isSitting, ArrayList(calibrationBuffer))
                    calibrationBuffer.clear()
                }
            }
        }
        calibrationHandler.postDelayed(updateRunnable, 1000L)
    }

    private fun cancelCalibration() {
        if (isCalibrating) {
            isCalibrating = false
            calibrationHandler.removeCallbacksAndMessages(null)
            calibrationDialog?.dismiss()
            calibrationDialog = null
            calibrationBuffer.clear()
        }
    }

    private fun computeAndSaveCalibration(isSitting: Boolean, samples: List<FloatArray>) {
        if (samples.isEmpty()) {
            Toast.makeText(this, R.string.alarm_calibration_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val n = samples.size
        val meanX = samples.map { it[0] }.average().toFloat()
        val meanY = samples.map { it[1] }.average().toFloat()
        val meanZ = samples.map { it[2] }.average().toFloat()
        val magnitudes = samples.map { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
        val meanMag = magnitudes.average()
        val stdMag = sqrt(magnitudes.map { (it - meanMag) * (it - meanMag) }.average()).toFloat()

        val features = PostureFeatures(meanX, meanY, meanZ, stdMag, n)

        // 追加到现有样本列表（保留另一姿态与已有样本），支持不同体位多次采集
        val existing = PostureCalibration.fromJson(current.alarm.postureCalibrationJson)
        val sitSamples = existing?.sittingSamples ?: emptyList()
        val standSamples = existing?.standingSamples ?: emptyList()
        val updated = if (isSitting) {
            PostureCalibration(
                sittingSamples = sitSamples + features,
                standingSamples = standSamples,
                motionThreshold = existing?.motionThreshold ?: 1.5f,
                calibratedAt = System.currentTimeMillis()
            )
        } else {
            PostureCalibration(
                sittingSamples = sitSamples,
                standingSamples = standSamples + features,
                motionThreshold = existing?.motionThreshold ?: 1.5f,
                calibratedAt = System.currentTimeMillis()
            )
        }

        settings.setAsync(SettingsKeys.POSTURE_CALIBRATION_DATA, updated.toJson())
        postureDetector.setCalibration(updated)
        refreshCalibrationStatus()

        val postureName =
            getString(if (isSitting) R.string.posture_sitting else R.string.posture_standing)
        val total = if (isSitting) updated.sittingSamples.size else updated.standingSamples.size
        Toast.makeText(
            this,
            getString(R.string.alarm_posture_collected, postureName, total),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshCalibrationStatus() {
        val cal = PostureCalibration.fromJson(current.alarm.postureCalibrationJson)
        val sitStatus = if (cal?.sittingSamples?.isNotEmpty() == true) {
            getString(R.string.alarm_calibrated_samples, cal.sittingSamples.size)
        } else {
            getString(R.string.alarm_not_calibrated)
        }
        val standStatus = if (cal?.standingSamples?.isNotEmpty() == true) {
            getString(R.string.alarm_calibrated_samples, cal.standingSamples.size)
        } else {
            getString(R.string.alarm_not_calibrated)
        }
        binding.calibrationStatus.text =
            getString(R.string.alarm_calibration_status, sitStatus, standStatus)
    }

    // ========== 预警设置 ==========

    private fun setupAlarmSettings() {
        // 启用开关
        val isEnabled = current.alarm.enabled
        binding.alarmEnabledSwitch.isChecked = isEnabled
        binding.alarmEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.ALARM_ENABLED, isChecked)
            if (isChecked) {
                startService(Intent(this, HeartRateAlarmService::class.java))
            } else {
                stopService(Intent(this, HeartRateAlarmService::class.java))
            }
        }

        // 超过范围：80-180 BPM（Slider 直接使用真实值域）
        setupSlider(binding.highThresholdSeekBar, current.alarm.highThreshold) { value ->
            binding.highThresholdValue.text = "$value BPM"
            settings.setAsync(SettingsKeys.ALARM_HIGH_THRESHOLD, value)
        }
        binding.highThresholdValue.text = "${current.alarm.highThreshold} BPM"

        // 低于范围：30-80 BPM
        setupSlider(binding.lowThresholdSeekBar, current.alarm.lowThreshold) { value ->
            binding.lowThresholdValue.text = "$value BPM"
            settings.setAsync(SettingsKeys.ALARM_LOW_THRESHOLD, value)
        }
        binding.lowThresholdValue.text = "${current.alarm.lowThreshold} BPM"

        // 持续时长：5-60 秒
        setupSlider(binding.durationSeekBar, current.alarm.durationSeconds) { value ->
            binding.durationValue.text = getString(R.string.alarm_duration_seconds, value)
            settings.setAsync(SettingsKeys.ALARM_DURATION_SECONDS, value)
        }
        binding.durationValue.text =
            getString(R.string.alarm_duration_seconds, current.alarm.durationSeconds)

        // 重复报警开关
        val isRepeatEnabled = current.alarm.repeatEnabled
        binding.repeatAlarmSwitch.isChecked = isRepeatEnabled
        binding.repeatIntervalContainer.visibility = if (isRepeatEnabled) View.VISIBLE else View.GONE
        binding.repeatAlarmSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.ALARM_REPEAT_ENABLED, isChecked)
            binding.repeatIntervalContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 报警间隔：1-30 分钟
        setupSlider(binding.repeatIntervalSeekBar, current.alarm.repeatIntervalMinutes) { value ->
            binding.repeatIntervalValue.text = getString(R.string.alarm_interval_minutes, value)
            settings.setAsync(SettingsKeys.ALARM_REPEAT_INTERVAL_MINUTES, value)
        }
        binding.repeatIntervalValue.text =
            getString(R.string.alarm_interval_minutes, current.alarm.repeatIntervalMinutes)
    }

    /** Slider 使用真实值域（valueFrom/valueTo 定义在布局），存储与显示无需偏移换算 */
    private fun setupSlider(slider: Slider, initialValue: Int, onValue: (Int) -> Unit) {
        slider.value = initialValue.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) onValue(value.toInt())
        }
    }

    companion object {
        private const val CLASSIFY_INTERVAL_MS = 200L
        private const val CALIBRATION_DURATION_SECONDS = 10
    }
}
