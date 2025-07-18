package com.example.heart_rate_monitor_mobile.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.databinding.ActivitySettingsBinding
import com.example.heart_rate_monitor_mobile.ui.server.ServerActivity
import com.example.heart_rate_monitor_mobile.ui.webhook.WebhookActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)

        setupToolbar()
        setupClickListeners()
        displayAppVersion()
        setupSwitches()
        setupFloatingWindowSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
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
    }

    private fun displayAppVersion() {
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            binding.appVersionText.text = version
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            binding.appVersionText.text = "N/A"
        }
    }

    private fun setupSwitches() {
        // 【核心修改】将默认值改为false，并添加带提醒的监听器
        binding.historyRecordingSwitch.isChecked = sharedPreferences.getBoolean("history_recording_enabled", false)
        binding.historyRecordingSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 当用户尝试打开开关时，显示警告对话框
                MaterialAlertDialogBuilder(this)
                    .setTitle("性能提醒")
                    .setMessage("开启心率记录功能将在每次连接期间持续将数据写入手机存储，这可能会略微增加电池消耗。确定要开启吗？")
                    .setNegativeButton("取消") { _, _ ->
                        // 如果用户取消，将开关恢复到关闭状态
                        buttonView.isChecked = false
                    }
                    .setPositiveButton("确定") { _, _ ->
                        // 如果用户确认，保存设置
                        sharedPreferences.edit().putBoolean("history_recording_enabled", true).apply()
                    }
                    .show()
            } else {
                // 关闭开关时，直接保存设置
                sharedPreferences.edit().putBoolean("history_recording_enabled", false).apply()
            }
        }


        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)
        binding.heartbeatAnimationSwitch.isChecked = isAnimationEnabled
        binding.heartbeatAnimationSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heartbeat_animation_enabled", isChecked).apply()
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
    }

    private fun setupFloatingWindowSettings() {
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

    private fun showColorPicker(prefKey: String, title: String, defaultColor: Int) {
        ColorPickerDialog.Builder(this)
            .setTitle(title)
            .setPreferenceName("ColorPickerDialog")
            .attachBrightnessSlideBar(true)
            .attachAlphaSlideBar(false)
            .setPositiveButton("确定", object : ColorEnvelopeListener {
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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}