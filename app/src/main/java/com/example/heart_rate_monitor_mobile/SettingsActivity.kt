package com.example.heart_rate_monitor_mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.databinding.ActivitySettingsBinding
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setupToolbar()
        setupClickListeners()
        displayAppVersion()
        setupSwitches()
        // 初始化悬浮窗设置
        setupFloatingWindowSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"
    }

    private fun setupClickListeners() {
        binding.githubLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ccc007ccc/HeartRateMonitorMobile"))
            startActivity(intent)
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

        // BPM 文本开关
        val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true)
        binding.bpmTextSwitch.isChecked = isBpmTextEnabled
        binding.bpmTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("bpm_text_enabled", isChecked).apply()
        }

        // 心率图标开关
        val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true)
        binding.heartIconSwitch.isChecked = isHeartIconEnabled
        binding.heartIconSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("heart_icon_enabled", isChecked).apply()
        }
    }

    /**
     * 初始化所有悬浮窗相关的设置项
     */
    private fun setupFloatingWindowSettings() {
        // --- 颜色选择器 ---
        binding.textColorPreview.setOnClickListener {
            showColorPicker("floating_text_color", "文本颜色", Color.BLACK)
        }
        binding.bgColorPreview.setOnClickListener {
            showColorPicker("floating_bg_color", "背景颜色", Color.BLACK)
        }
        binding.borderColorPreview.setOnClickListener {
            showColorPicker("floating_border_color", "边框颜色", Color.GRAY)
        }

        // --- SeekBars ---
        setupSeekBar(binding.bgAlphaSeekBar, "floating_bg_alpha", 10)
        setupSeekBar(binding.borderAlphaSeekBar, "floating_border_alpha", 100)
        setupSeekBar(binding.cornerRadiusSeekBar, "floating_corner_radius", 100)
        setupSeekBar(binding.sizeSeekBar, "floating_size", 100)
        setupSeekBar(binding.iconSizeSeekBar, "floating_icon_size", 100) // 图标大小

        // --- 加载初始值 ---
        updateColorPreviews()
    }

    private fun showColorPicker(prefKey: String, title: String, defaultColor: Int) {
        val currentColor = sharedPreferences.getInt(prefKey, defaultColor)
        ColorPickerDialog
            .Builder(this)
            .setTitle(title)
            .setColorShape(ColorShape.SQAURE)
            .setDefaultColor(currentColor)
            .setColorListener { color, _ ->
                sharedPreferences.edit().putInt(prefKey, color).apply()
                updateColorPreviews()
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