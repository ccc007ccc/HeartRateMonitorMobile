package com.example.heart_rate_monitor_mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setupToolbar()
        setupClickListeners()
        displayAppVersion()
        setupAnimationSwitch()
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

    private fun setupAnimationSwitch() {
        // Load the saved setting and set the switch state
        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)
        binding.heartbeatAnimationSwitch.isChecked = isAnimationEnabled

        // Save the setting when the switch is toggled
        binding.heartbeatAnimationSwitch.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean("heartbeat_animation_enabled", isChecked)
                apply()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}