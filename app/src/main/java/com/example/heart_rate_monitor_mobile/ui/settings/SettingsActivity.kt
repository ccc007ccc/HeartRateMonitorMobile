package com.example.heart_rate_monitor_mobile.ui.settings

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.service.StatusBarResidentService
import com.example.heart_rate_monitor_mobile.databinding.ActivitySettingsBinding
import com.example.heart_rate_monitor_mobile.ui.favorite.FavoriteDevicesActivity
import com.example.heart_rate_monitor_mobile.ui.alarm.HeartRateAlarmActivity
import com.example.heart_rate_monitor_mobile.service.HeartRateAlarmService
import com.example.heart_rate_monitor_mobile.ui.server.ServerActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.example.heart_rate_monitor_mobile.ui.webhook.WebhookActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    /**
     * MediaProjection 权限请求 launcher。
     * 用户授权后，把 resultCode + data 通过 Intent extra 传给 StatusBarResidentService 启动采样。
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 写 pref，通知 Service 启动采样
            sharedPreferences.edit().putBoolean("status_bar_auto_color", true).apply()
            binding.statusBarAutoColorSwitch.isChecked = true
            updateWhiteTextSwitchEnabledState()
            val intent = Intent(this, StatusBarResidentService::class.java).apply {
                action = StatusBarResidentService.ACTION_START_MEDIA_PROJECTION
                putExtra(
                    StatusBarResidentService.EXTRA_RESULT_CODE,
                    result.resultCode
                )
                putExtra(
                    StatusBarResidentService.EXTRA_RESULT_DATA,
                    result.data
                )
            }
            startService(intent)
        } else {
            // 用户拒绝授权，回退开关
            binding.statusBarAutoColorSwitch.isChecked = false
            sharedPreferences.edit().putBoolean("status_bar_auto_color", false).apply()
            updateWhiteTextSwitchEnabledState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)

        setupToolbar()
        setupClickListeners()
        displayAppVersion()
        setupSwitches()
        setupFloatingWindowSettings()
        setupStatusBarSettings()
        recoverStatusBarResidentIfNeeded()
        recoverHeartRateAlarmIfNeeded()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // 汉化：标题
        supportActionBar?.title = "设置"
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.githubLink.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/ccc007ccc/HeartRateMonitorMobile")
            )
            startActivity(intent)
        }

        binding.serverSettingsLink.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }

        binding.webhookSettingsLink.setOnClickListener {
            startActivity(Intent(this, WebhookActivity::class.java))
        }

        binding.favoriteDevicesLink.setOnClickListener {
            startActivity(Intent(this, FavoriteDevicesActivity::class.java))
        }

        binding.heartRateAlarmLink.setOnClickListener {
            startActivity(Intent(this, HeartRateAlarmActivity::class.java))
        }
    }

    private fun displayAppVersion() {
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            binding.appVersionText.text = version
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            // 汉化：版本获取失败提示
            binding.appVersionText.text = "未知"
        }
    }

    private fun setupSwitches() {
        // 【关键修复】设置开关的颜色状态列表，修复关闭时颜色异常问题
        // 使用 ?attr/colorPrimary 解析后的颜色，开启莫奈取色时随动态主题色变化
        val activeColor = MaterialColors.getColor(
            this,
            android.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.primary_light)
        )
        val inactiveTrackColor = Color.parseColor("#E0E0E0") // 浅灰色轨道
        val inactiveThumbColor = Color.WHITE // 白色滑块

        val thumbStates = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked), // 关闭状态
                intArrayOf(android.R.attr.state_checked)  // 开启状态
            ),
            intArrayOf(
                inactiveThumbColor,
                activeColor
            )
        )

        val trackStates = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(
                inactiveTrackColor,
                ColorUtils.setAlphaComponent(activeColor, 128) // 开启时轨道半透明
            )
        )

        val switches = listOf(
            binding.historyRecordingSwitch,
            binding.heartbeatAnimationSwitch,
            binding.monetColorSwitch,
            binding.autoConnectSwitch,
            binding.autoReconnectSwitch,
            binding.bpmTextSwitch,
            binding.heartIconSwitch,
            binding.speedDisplaySwitch,
            binding.statusBarResidentSwitch,
            binding.statusBarBpmTextSwitch,
            binding.statusBarAutoColorSwitch,
            binding.statusBarWhiteTextSwitch
        )

        switches.forEach { switch ->
            switch.thumbTintList = thumbStates
            switch.trackTintList = trackStates
        }

        // 绑定逻辑
        binding.historyRecordingSwitch.isChecked = sharedPreferences.getBoolean("history_recording_enabled", false)
        binding.historyRecordingSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 汉化：性能警告对话框
                MaterialAlertDialogBuilder(this)
                    .setTitle("性能警告")
                    .setMessage("开启历史记录将持续写入数据到存储，可能会增加耗电量。确认开启吗？")
                    .setNegativeButton("取消") { _, _ ->
                        buttonView.isChecked = false
                    }
                    .setPositiveButton("确认") { _, _ ->
                        sharedPreferences.edit().putBoolean("history_recording_enabled", true).apply()
                    }
                    .show()
            } else {
                sharedPreferences.edit().putBoolean("history_recording_enabled", false).apply()
            }
        }

        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)
        binding.heartbeatAnimationSwitch.isChecked = isAnimationEnabled
        binding.heartbeatAnimationSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heartbeat_animation_enabled", isChecked).apply()
        }

        // 莫奈取色（Material You 动态取色）：已锁定为常开，不允许用户关闭
        // 强制写入 true，处理旧版本用户曾关闭过的情况
        sharedPreferences.edit().putBoolean("monet_color_enabled", true).apply()
        binding.monetColorSwitch.isChecked = true
        binding.monetColorSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            // 阻止关闭：用户尝试关闭时强制恢复开启状态
            if (!isChecked) {
                buttonView.isChecked = true
            }
        }

        val isAutoConnectEnabled = sharedPreferences.getBoolean("auto_connect_enabled", false)
        binding.autoConnectSwitch.isChecked = isAutoConnectEnabled
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("auto_connect_enabled", isChecked).apply()
        }

        val isAutoReconnectEnabled = sharedPreferences.getBoolean("auto_reconnect_enabled", true)
        binding.autoReconnectSwitch.isChecked = isAutoReconnectEnabled
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("auto_reconnect_enabled", isChecked).apply()
        }

        val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true)
        binding.bpmTextSwitch.isChecked = isBpmTextEnabled
        binding.bpmTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("bpm_text_enabled", isChecked).apply()
        }

        val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true)
        binding.heartIconSwitch.isChecked = isHeartIconEnabled
        binding.heartIconSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heart_icon_enabled", isChecked).apply()
        }

        val isSpeedDisplayEnabled = sharedPreferences.getBoolean("speed_display_enabled", false)
        binding.speedDisplaySwitch.isChecked = isSpeedDisplayEnabled
        binding.speedDisplaySwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 汉化：速度显示警告对话框
                MaterialAlertDialogBuilder(this)
                    .setTitle("开启速度显示")
                    .setMessage("该功能使用 GPS 计算速度，可能会增加耗电量并需要定位权限。确认开启吗？")
                    .setNegativeButton("取消") { _, _ ->
                        buttonView.isChecked = false
                    }
                    .setPositiveButton("确认") { _, _ ->
                        sharedPreferences.edit().putBoolean("speed_display_enabled", true).apply()
                    }
                    .show()
            } else {
                sharedPreferences.edit().putBoolean("speed_display_enabled", false).apply()
            }
        }

        val isStatusBarResidentEnabled = sharedPreferences.getBoolean("status_bar_resident_enabled", false)
        binding.statusBarResidentSwitch.isChecked = isStatusBarResidentEnabled
        binding.statusBarResidentSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 开启：先校验悬浮窗权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    // 未授权：跳转权限页，回退开关状态，不写 pref
                    buttonView.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    sharedPreferences.edit().putBoolean("status_bar_resident_enabled", true).apply()
                    startService(Intent(this, StatusBarResidentService::class.java))
                }
            } else {
                sharedPreferences.edit().putBoolean("status_bar_resident_enabled", false).apply()
                stopService(Intent(this, StatusBarResidentService::class.java))
            }
        }
    }

    private fun setupFloatingWindowSettings() {
        // 汉化：颜色选择器标题
        binding.textColorPreview.setOnClickListener {
            showColorPicker("floating_text_color", "文本颜色", Color.BLACK)
        }
        binding.bgColorPreview.setOnClickListener {
            showColorPicker("floating_bg_color", "背景颜色", Color.BLACK)
        }
        binding.borderColorPreview.setOnClickListener {
            showColorPicker("floating_border_color", "边框颜色", Color.GRAY)
        }

        setupSeekBar(binding.bgAlphaSeekBar, "floating_bg_alpha", 10)
        setupSeekBar(binding.borderAlphaSeekBar, "floating_border_alpha", 100)
        setupSeekBar(binding.cornerRadiusSeekBar, "floating_corner_radius", 100)
        setupSeekBar(binding.sizeSeekBar, "floating_size", 100)
        setupSeekBar(binding.iconSizeSeekBar, "floating_icon_size", 100)

        updateColorPreviews()
    }

    private fun setupStatusBarSettings() {
        setupSeekBar(binding.statusBarXPositionSeekBar, "status_bar_x_position", 0)
        setupSeekBar(binding.statusBarYOffsetSeekBar, "status_bar_y_offset", 10)
        setupSeekBar(binding.statusBarSizeSeekBar, "status_bar_size", 100)
        setupSeekBar(binding.statusBarTextThicknessSeekBar, "status_bar_text_thickness", 0)

        // BPM 文字显示开关：默认开启
        val isBpmTextEnabled = sharedPreferences.getBoolean("status_bar_bpm_text_enabled", true)
        binding.statusBarBpmTextSwitch.isChecked = isBpmTextEnabled
        binding.statusBarBpmTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("status_bar_bpm_text_enabled", isChecked).apply()
        }

        // 自动识别屏幕颜色开关（MediaProjection 截屏采样）
        val isAutoColorEnabled = sharedPreferences.getBoolean("status_bar_auto_color", false)
        binding.statusBarAutoColorSwitch.isChecked = isAutoColorEnabled
        binding.statusBarAutoColorSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 前置：状态栏常驻必须已开启
                if (!sharedPreferences.getBoolean("status_bar_resident_enabled", false)) {
                    buttonView.isChecked = false
                    MaterialAlertDialogBuilder(this)
                        .setTitle("提示")
                        .setMessage("请先开启“状态栏常驻心率”开关后再使用自动识别。")
                        .setPositiveButton("知道了", null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                // 请求 MediaProjection 权限（系统会弹授权对话框）
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                // 关闭：写 pref + 通知 Service 停止采样
                sharedPreferences.edit().putBoolean("status_bar_auto_color", false).apply()
                updateWhiteTextSwitchEnabledState()
                val intent = Intent(this, StatusBarResidentService::class.java).apply {
                    action = StatusBarResidentService.ACTION_STOP_MEDIA_PROJECTION
                }
                startService(intent)
            }
        }

        // 手动白色文字开关（仅在自动识别关闭时生效）
        val isWhiteTextEnabled = sharedPreferences.getBoolean("status_bar_white_text", false)
        binding.statusBarWhiteTextSwitch.isChecked = isWhiteTextEnabled
        binding.statusBarWhiteTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("status_bar_white_text", isChecked).apply()
        }

        updateWhiteTextSwitchEnabledState()
    }

    /**
     * 自动识别开启时禁用手动白色文字开关（自动模式覆盖手动选择）。
     */
    private fun updateWhiteTextSwitchEnabledState() {
        val autoColor = sharedPreferences.getBoolean("status_bar_auto_color", false)
        binding.statusBarWhiteTextSwitch.isEnabled = !autoColor
        binding.statusBarWhiteTextSwitch.alpha = if (autoColor) 0.4f else 1f
    }

    private fun showColorPicker(prefKey: String, title: String, defaultColor: Int) {
        ColorPickerDialog.Builder(this)
            .setTitle(title)
            .setPreferenceName("ColorPickerDialog")
            .attachBrightnessSlideBar(true)
            .attachAlphaSlideBar(false)
            // 汉化：颜色选择器按钮
            .setPositiveButton("确认", object : ColorEnvelopeListener {
                override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                    envelope?.let {
                        sharedPreferences.edit().putInt(prefKey, it.color).apply()
                        updateColorPreviews()
                    }
                }
            })
            .setNegativeButton("取消") { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
            }
            .show()
    }

    private fun setupSeekBar(seekBar: SeekBar, prefKey: String, defaultValue: Int) {
        seekBar.progress = sharedPreferences.getInt(prefKey, defaultValue)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sharedPreferences.edit().putInt(prefKey, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateColorPreviews() {
        binding.textColorPreview.setBackgroundColor(sharedPreferences.getInt("floating_text_color", Color.BLACK))
        binding.bgColorPreview.setBackgroundColor(sharedPreferences.getInt("floating_bg_color", Color.BLACK))
        binding.borderColorPreview.setBackgroundColor(sharedPreferences.getInt("floating_border_color", Color.GRAY))
    }

    /**
     * 兜底恢复：App 被 force-stop 后进程被杀，pref 仍为 true 但 overlay 消失。
     * 重进设置页（onCreate 冷启动）时若权限仍在，则重新拉起服务。
     */
    private fun recoverStatusBarResidentIfNeeded() {
        val enabled = sharedPreferences.getBoolean("status_bar_resident_enabled", false)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startService(Intent(this, StatusBarResidentService::class.java))
        }
    }

    /**
     * 兜底恢复：App 被 force-stop 后进程被杀，pref 仍为 true 但服务已停止。
     * 重进设置页（onCreate 冷启动）时重新拉起服务。
     * 心率预警服务无需特殊运行时权限（VIBRATE 为普通权限），直接检查 pref 即可。
     */
    private fun recoverHeartRateAlarmIfNeeded() {
        val enabled = sharedPreferences.getBoolean("heart_rate_alarm_enabled", false)
        if (enabled) {
            startService(Intent(this, HeartRateAlarmService::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}