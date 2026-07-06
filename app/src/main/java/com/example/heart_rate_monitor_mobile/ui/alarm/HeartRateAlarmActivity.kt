package com.example.heart_rate_monitor_mobile.ui.alarm

import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.ActivityHeartRateAlarmBinding
import com.example.heart_rate_monitor_mobile.service.HeartRateAlarmService
import com.example.heart_rate_monitor_mobile.service.posture.PostureCalibration
import com.example.heart_rate_monitor_mobile.service.posture.PostureDetector
import com.example.heart_rate_monitor_mobile.service.posture.PostureFeatures
import com.example.heart_rate_monitor_mobile.service.posture.PostureType
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
class HeartRateAlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHeartRateAlarmBinding
    private lateinit var sharedPreferences: SharedPreferences
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

        sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        postureDetector = PostureDetector()
        postureDetector.setCalibration(
            PostureCalibration.fromJson(sharedPreferences.getString("posture_calibration_data", null))
        )

        setupToolbar()
        setupSwitchTint()
        setupAlarmSettings()
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

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "心率预警"
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
        binding.postureLabel.text = posture.label

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
    }

    private fun startCalibration(isSitting: Boolean) {
        val postureName = if (isSitting) "静坐" else "站立"
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
            text = "请保持${postureName}姿势… 剩余 ${CALIBRATION_DURATION_SECONDS} 秒"
            setPadding(0, 16, 0, 0)
        }
        container.addView(progressBar)
        container.addView(messageView)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("校准${postureName}")
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
                    messageView.text = "请保持${postureName}姿势… 剩余 ${remaining} 秒"
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
            Toast.makeText(this, "校准失败：未采集到数据", Toast.LENGTH_SHORT).show()
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

        // 合并入现有校准数据（保留另一姿态）
        val existing = PostureCalibration.fromJson(
            sharedPreferences.getString("posture_calibration_data", null)
        )
        val updated = if (isSitting) {
            PostureCalibration(features, existing?.standing, existing?.motionThreshold ?: 1.5f,
                System.currentTimeMillis())
        } else {
            PostureCalibration(existing?.sitting, features, existing?.motionThreshold ?: 1.5f,
                System.currentTimeMillis())
        }

        sharedPreferences.edit().putString("posture_calibration_data", updated.toJson()).apply()
        postureDetector.setCalibration(updated)
        refreshCalibrationStatus()

        val postureName = if (isSitting) "静坐" else "站立"
        Toast.makeText(this, "${postureName}姿态校准完成（${n} 个样本）", Toast.LENGTH_SHORT).show()
    }

    private fun refreshCalibrationStatus() {
        val cal = PostureCalibration.fromJson(
            sharedPreferences.getString("posture_calibration_data", null)
        )
        val sitStatus = if (cal?.sitting != null) "已校准 ✓" else "未校准"
        val standStatus = if (cal?.standing != null) "已校准 ✓" else "未校准"
        binding.calibrationStatus.text = "静坐：$sitStatus    站立：$standStatus"
    }

    // ========== 预警设置 ==========

    private fun setupAlarmSettings() {
        // 启用开关
        val isEnabled = sharedPreferences.getBoolean("heart_rate_alarm_enabled", false)
        binding.alarmEnabledSwitch.isChecked = isEnabled
        binding.alarmEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heart_rate_alarm_enabled", isChecked).apply()
            if (isChecked) {
                startService(Intent(this, HeartRateAlarmService::class.java))
            } else {
                stopService(Intent(this, HeartRateAlarmService::class.java))
            }
        }

        // 超过范围：progress 0-100 → 80-180 BPM
        val highValue = sharedPreferences.getInt("heart_rate_alarm_high_threshold", 100)
        binding.highThresholdSeekBar.progress = highValue - HIGH_THRESHOLD_MIN
        binding.highThresholdValue.text = "$highValue BPM"
        binding.highThresholdSeekBar.setOnSeekBarChangeListener(
            simpleSeekBarListener { progress ->
                val value = HIGH_THRESHOLD_MIN + progress
                binding.highThresholdValue.text = "$value BPM"
                sharedPreferences.edit().putInt("heart_rate_alarm_high_threshold", value).apply()
            }
        )

        // 低于范围：progress 0-50 → 30-80 BPM
        val lowValue = sharedPreferences.getInt("heart_rate_alarm_low_threshold", 50)
        binding.lowThresholdSeekBar.progress = lowValue - LOW_THRESHOLD_MIN
        binding.lowThresholdValue.text = "$lowValue BPM"
        binding.lowThresholdSeekBar.setOnSeekBarChangeListener(
            simpleSeekBarListener { progress ->
                val value = LOW_THRESHOLD_MIN + progress
                binding.lowThresholdValue.text = "$value BPM"
                sharedPreferences.edit().putInt("heart_rate_alarm_low_threshold", value).apply()
            }
        )

        // 持续时长：progress 0-55 → 5-60 秒
        val durValue = sharedPreferences.getInt("heart_rate_alarm_duration_seconds", 10)
        binding.durationSeekBar.progress = durValue - DURATION_MIN
        binding.durationValue.text = "$durValue 秒"
        binding.durationSeekBar.setOnSeekBarChangeListener(
            simpleSeekBarListener { progress ->
                val value = DURATION_MIN + progress
                binding.durationValue.text = "$value 秒"
                sharedPreferences.edit().putInt("heart_rate_alarm_duration_seconds", value).apply()
            }
        )

        // 重复报警开关
        val isRepeatEnabled = sharedPreferences.getBoolean("heart_rate_alarm_repeat_enabled", false)
        binding.repeatAlarmSwitch.isChecked = isRepeatEnabled
        binding.repeatIntervalContainer.visibility = if (isRepeatEnabled) View.VISIBLE else View.GONE
        binding.repeatAlarmSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heart_rate_alarm_repeat_enabled", isChecked).apply()
            binding.repeatIntervalContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 报警间隔：progress 0-29 → 1-30 分钟
        val intervalValue = sharedPreferences.getInt("heart_rate_alarm_repeat_interval_minutes", 5)
        binding.repeatIntervalSeekBar.progress = intervalValue - REPEAT_INTERVAL_MIN
        binding.repeatIntervalValue.text = "$intervalValue 分钟"
        binding.repeatIntervalSeekBar.setOnSeekBarChangeListener(
            simpleSeekBarListener { progress ->
                val value = REPEAT_INTERVAL_MIN + progress
                binding.repeatIntervalValue.text = "$value 分钟"
                sharedPreferences.edit().putInt("heart_rate_alarm_repeat_interval_minutes", value).apply()
            }
        )
    }

    private fun simpleSeekBarListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    companion object {
        private const val CLASSIFY_INTERVAL_MS = 200L
        private const val CALIBRATION_DURATION_SECONDS = 10
        private const val HIGH_THRESHOLD_MIN = 80
        private const val LOW_THRESHOLD_MIN = 30
        private const val DURATION_MIN = 5
        private const val REPEAT_INTERVAL_MIN = 1
    }
}
