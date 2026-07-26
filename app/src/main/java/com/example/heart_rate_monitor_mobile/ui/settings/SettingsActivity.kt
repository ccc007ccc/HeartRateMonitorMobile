package com.example.heart_rate_monitor_mobile.ui.settings

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.datastore.preferences.core.Preferences
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivitySettingsBinding
import com.example.heart_rate_monitor_mobile.service.HeartRateAlarmService
import com.example.heart_rate_monitor_mobile.service.StatusBarResidentService
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.ui.alarm.HeartRateAlarmActivity
import com.example.heart_rate_monitor_mobile.ui.favorite.FavoriteDevicesActivity
import com.example.heart_rate_monitor_mobile.ui.server.ServerActivity
import com.example.heart_rate_monitor_mobile.ui.webhook.WebhookActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val settings get() = container.settings
    private val current get() = settings.settings.value

    /**
     * MediaProjection 权限请求 launcher。
     * 用户授权后，把 resultCode + data 通过 Intent extra 传给 StatusBarResidentService 启动采样。
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            settings.setAsync(SettingsKeys.STATUS_BAR_AUTO_COLOR, true)
            binding.statusBarAutoColorSwitch.isChecked = true
            updateWhiteTextSwitchEnabledState(autoColor = true)
            val intent = Intent(this, StatusBarResidentService::class.java).apply {
                action = StatusBarResidentService.ACTION_START_MEDIA_PROJECTION
                putExtra(StatusBarResidentService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StatusBarResidentService.EXTRA_RESULT_DATA, result.data)
            }
            startService(intent)
        } else {
            // 用户拒绝授权，回退开关
            binding.statusBarAutoColorSwitch.isChecked = false
            settings.setAsync(SettingsKeys.STATUS_BAR_AUTO_COLOR, false)
            updateWhiteTextSwitchEnabledState(autoColor = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        setupToolbar()
        setupClickListeners()
        displayAppVersion()
        setupSwitches()
        setupFloatingWindowSettings()
        setupStatusBarSettings()
        observeSectionVisibility()
        recoverStatusBarResidentIfNeeded()
        recoverHeartRateAlarmIfNeeded()
    }

    /**
     * 强关联调节项收纳：功能开关关闭时对应的调节区没必要显示。
     * 由设置流驱动（而非本地点击回调），主页切换悬浮窗开关后回到本页也能正确同步。
     */
    private fun observeSectionVisibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    settings.flowOf { it.statusBar.residentEnabled }.collect { enabled ->
                        binding.statusBarOptionsContainer.visibility =
                            if (enabled) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    settings.flowOf { it.floating.enabled }.collect { enabled ->
                        val visibility = if (enabled) View.VISIBLE else View.GONE
                        binding.floatingSectionTitle.visibility = visibility
                        binding.floatingSectionCard.visibility = visibility
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.githubLink.setOnClickListener {
            suppressHideForExternalLaunch = true
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ccc007ccc/HeartRateMonitorMobile"))
            )
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
            binding.appVersionText.text = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            binding.appVersionText.text = getString(R.string.common_unknown)
        }
    }

    private fun setupSwitches() {
        // 设置开关的颜色状态列表：使用解析后的 colorPrimary，开启莫奈取色时随动态主题色变化
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

        listOf(
            binding.historyRecordingSwitch,
            binding.heartbeatAnimationSwitch,
            binding.monetColorSwitch,
            binding.autoConnectSwitch,
            binding.autoReconnectSwitch,
            binding.bpmTextSwitch,
            binding.heartIconSwitch,
            binding.speedDisplaySwitch,
            binding.hideFromRecentsSwitch,
            binding.statusBarResidentSwitch,
            binding.statusBarBpmTextSwitch,
            binding.statusBarAutoColorSwitch,
            binding.statusBarWhiteTextSwitch,
        ).forEach { switch ->
            switch.thumbTintList = thumbStates
            switch.trackTintList = trackStates
        }

        // 历史记录（开启前弹性能警告）
        binding.historyRecordingSwitch.isChecked = current.general.historyRecordingEnabled
        binding.historyRecordingSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_perf_warning_title)
                    .setMessage(R.string.settings_perf_warning_message)
                    .setNegativeButton(R.string.common_cancel) { _, _ -> buttonView.isChecked = false }
                    .setPositiveButton(R.string.common_confirm) { _, _ ->
                        settings.setAsync(SettingsKeys.HISTORY_RECORDING_ENABLED, true)
                    }
                    .show()
            } else {
                settings.setAsync(SettingsKeys.HISTORY_RECORDING_ENABLED, false)
            }
        }

        binding.heartbeatAnimationSwitch.isChecked = current.general.heartbeatAnimationEnabled
        binding.heartbeatAnimationSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.HEARTBEAT_ANIMATION_ENABLED, isChecked)
        }

        // 莫奈取色（Material You 动态取色）：可自由开关；
        // 主题 overlay 只在 Activity 创建时应用，切换后重建本页立即生效（主页在 onResume 自行重建）
        binding.monetColorSwitch.isChecked = current.general.monetColorEnabled
        binding.monetColorSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settings.set(SettingsKeys.MONET_COLOR_ENABLED, isChecked)
                recreate()
            }
        }

        binding.autoConnectSwitch.isChecked = current.connection.autoConnectEnabled
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.AUTO_CONNECT_ENABLED, isChecked)
        }

        binding.autoReconnectSwitch.isChecked = current.connection.autoReconnectEnabled
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.AUTO_RECONNECT_ENABLED, isChecked)
        }

        binding.bpmTextSwitch.isChecked = current.floating.bpmTextEnabled
        binding.bpmTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.FLOATING_BPM_TEXT_ENABLED, isChecked)
        }

        binding.heartIconSwitch.isChecked = current.floating.heartIconEnabled
        binding.heartIconSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.FLOATING_HEART_ICON_ENABLED, isChecked)
        }

        // 速度显示（开启前弹 GPS 耗电警告）
        binding.speedDisplaySwitch.isChecked = current.general.speedDisplayEnabled
        binding.speedDisplaySwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_speed_warning_title)
                    .setMessage(R.string.settings_speed_warning_message)
                    .setNegativeButton(R.string.common_cancel) { _, _ -> buttonView.isChecked = false }
                    .setPositiveButton(R.string.common_confirm) { _, _ ->
                        settings.setAsync(SettingsKeys.SPEED_DISPLAY_ENABLED, true)
                    }
                    .show()
            } else {
                settings.setAsync(SettingsKeys.SPEED_DISPLAY_ENABLED, false)
            }
        }

        // 退出应用隐藏后台：开启后按 HOME 退出时从最近任务列表移除，进程由前台服务保活
        binding.hideFromRecentsSwitch.isChecked = current.general.hideFromRecentsEnabled
        binding.hideFromRecentsSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.HIDE_FROM_RECENTS_ENABLED, isChecked)
        }

        binding.statusBarResidentSwitch.isChecked = current.statusBar.residentEnabled
        binding.statusBarResidentSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 开启：先校验悬浮窗权限
                if (!Settings.canDrawOverlays(this)) {
                    buttonView.isChecked = false
                    suppressHideForExternalLaunch = true
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else {
                    settings.setAsync(SettingsKeys.STATUS_BAR_RESIDENT_ENABLED, true)
                    startService(Intent(this, StatusBarResidentService::class.java))
                }
            } else {
                settings.setAsync(SettingsKeys.STATUS_BAR_RESIDENT_ENABLED, false)
                stopService(Intent(this, StatusBarResidentService::class.java))
            }
        }
    }

    private fun setupFloatingWindowSettings() {
        binding.textColorPreview.setOnClickListener {
            showColorPicker(SettingsKeys.FLOATING_TEXT_COLOR, getString(R.string.floating_text_color_title))
        }
        binding.bgColorPreview.setOnClickListener {
            showColorPicker(SettingsKeys.FLOATING_BG_COLOR, getString(R.string.floating_bg_color_title))
        }
        binding.borderColorPreview.setOnClickListener {
            showColorPicker(SettingsKeys.FLOATING_BORDER_COLOR, getString(R.string.floating_border_color_title))
        }

        setupSeekBar(binding.bgAlphaSeekBar, SettingsKeys.FLOATING_BG_ALPHA, current.floating.backgroundAlphaPercent)
        setupSeekBar(binding.borderAlphaSeekBar, SettingsKeys.FLOATING_BORDER_ALPHA, current.floating.borderAlphaPercent)
        setupSeekBar(binding.cornerRadiusSeekBar, SettingsKeys.FLOATING_CORNER_RADIUS, current.floating.cornerRadius)
        setupSeekBar(binding.sizeSeekBar, SettingsKeys.FLOATING_SIZE, current.floating.sizePercent)
        setupSeekBar(binding.iconSizeSeekBar, SettingsKeys.FLOATING_ICON_SIZE, current.floating.iconSizePercent)

        updateColorPreviews()
    }

    private fun setupStatusBarSettings() {
        setupSeekBar(binding.statusBarXPositionSeekBar, SettingsKeys.STATUS_BAR_X_POSITION, current.statusBar.xPositionPercent)
        setupSeekBar(binding.statusBarYOffsetSeekBar, SettingsKeys.STATUS_BAR_Y_OFFSET, current.statusBar.yOffset)
        setupSeekBar(binding.statusBarSizeSeekBar, SettingsKeys.STATUS_BAR_SIZE, current.statusBar.sizePercent)
        setupSeekBar(binding.statusBarTextThicknessSeekBar, SettingsKeys.STATUS_BAR_TEXT_THICKNESS, current.statusBar.textThickness)

        binding.statusBarBpmTextSwitch.isChecked = current.statusBar.bpmTextEnabled
        binding.statusBarBpmTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.STATUS_BAR_BPM_TEXT_ENABLED, isChecked)
        }

        // 自动识别屏幕颜色开关（MediaProjection 截屏采样）
        binding.statusBarAutoColorSwitch.isChecked = current.statusBar.autoColor
        binding.statusBarAutoColorSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 前置：状态栏常驻必须已开启
                if (!current.statusBar.residentEnabled) {
                    buttonView.isChecked = false
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.settings_notice_title)
                        .setMessage(R.string.settings_statusbar_enable_first)
                        .setPositiveButton(R.string.common_got_it, null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                // 请求 MediaProjection 权限（系统会弹授权对话框）
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                // 关闭：写设置 + 通知 Service 停止采样
                settings.setAsync(SettingsKeys.STATUS_BAR_AUTO_COLOR, false)
                updateWhiteTextSwitchEnabledState(autoColor = false)
                startService(
                    Intent(this, StatusBarResidentService::class.java).apply {
                        action = StatusBarResidentService.ACTION_STOP_MEDIA_PROJECTION
                    }
                )
            }
        }

        // 手动白色文字开关（仅在自动识别关闭时生效）
        binding.statusBarWhiteTextSwitch.isChecked = current.statusBar.whiteText
        binding.statusBarWhiteTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.STATUS_BAR_WHITE_TEXT, isChecked)
        }

        updateWhiteTextSwitchEnabledState(autoColor = current.statusBar.autoColor)
    }

    /** 自动识别开启时禁用手动白色文字开关（自动模式覆盖手动选择） */
    private fun updateWhiteTextSwitchEnabledState(autoColor: Boolean) {
        binding.statusBarWhiteTextSwitch.isEnabled = !autoColor
        binding.statusBarWhiteTextSwitch.alpha = if (autoColor) 0.4f else 1f
    }

    private fun showColorPicker(prefKey: Preferences.Key<Int>, title: String) {
        ColorPickerDialog.Builder(this)
            .setTitle(title)
            .setPreferenceName("ColorPickerDialog")
            .attachBrightnessSlideBar(true)
            .attachAlphaSlideBar(false)
            .setPositiveButton(getString(R.string.common_confirm), object : ColorEnvelopeListener {
                override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                    envelope?.let {
                        settings.setAsync(prefKey, it.color)
                        // setAsync 为异步写，预览直接用选中值刷新
                        applyColorPreview(prefKey, it.color)
                    }
                }
            })
            .setNegativeButton(getString(R.string.common_cancel)) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
            }
            .show()
    }

    private fun setupSeekBar(slider: Slider, prefKey: Preferences.Key<Int>, initialValue: Int) {
        slider.value = initialValue.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.setAsync(prefKey, value.toInt())
            }
        }
    }

    private fun applyColorPreview(prefKey: Preferences.Key<Int>, color: Int) {
        when (prefKey) {
            SettingsKeys.FLOATING_TEXT_COLOR -> binding.textColorPreview.setBackgroundColor(color)
            SettingsKeys.FLOATING_BG_COLOR -> binding.bgColorPreview.setBackgroundColor(color)
            SettingsKeys.FLOATING_BORDER_COLOR -> binding.borderColorPreview.setBackgroundColor(color)
        }
    }

    private fun updateColorPreviews() {
        binding.textColorPreview.setBackgroundColor(current.floating.textColor)
        binding.bgColorPreview.setBackgroundColor(current.floating.backgroundColor)
        binding.borderColorPreview.setBackgroundColor(current.floating.borderColor)
    }

    /**
     * 兜底恢复：App 被 force-stop 后进程被杀，设置仍为 true 但 overlay 消失。
     * 重进设置页（onCreate 冷启动）时若权限仍在，则重新拉起服务。
     */
    private fun recoverStatusBarResidentIfNeeded() {
        if (current.statusBar.residentEnabled && Settings.canDrawOverlays(this)) {
            startService(Intent(this, StatusBarResidentService::class.java))
        }
    }

    /** 兜底恢复：同上，心率预警服务无需特殊运行时权限，直接检查设置即可 */
    private fun recoverHeartRateAlarmIfNeeded() {
        if (current.alarm.enabled) {
            startService(Intent(this, HeartRateAlarmService::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
