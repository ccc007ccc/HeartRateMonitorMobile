package com.example.heart_rate_monitor_mobile

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.databinding.ActivityServerBinding

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding
    private lateinit var sharedPreferences: SharedPreferences

    // 监听设置变化，实时更新UI
    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "http_server_enabled" || key == "http_server_port") {
            updateServerStatusUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)

        setupToolbar()
        setupViews()
        updateServerStatusUI()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupViews() {
        val isServerEnabled = sharedPreferences.getBoolean("http_server_enabled", false)
        val port = sharedPreferences.getInt("http_server_port", 8000)

        binding.serverSwitch.isChecked = isServerEnabled
        binding.portEditText.setText(port.toString())
        binding.portEditText.isEnabled = !isServerEnabled

        binding.serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            // 只更新SharedPreferences，服务会自动响应
            sharedPreferences.edit().putBoolean("http_server_enabled", isChecked).apply()
            binding.portEditText.isEnabled = !isChecked
        }

        binding.portEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newPort = v.text.toString().toIntOrNull() ?: 8000
                // 只更新SharedPreferences，服务会自动响应
                sharedPreferences.edit().putInt("http_server_port", newPort).apply()
                v.clearFocus()
                true
            } else {
                false
            }
        }
    }

    // 更新UI，显示服务器的当前状态和访问地址
    private fun updateServerStatusUI() {
        val isEnabled = sharedPreferences.getBoolean("http_server_enabled", false)
        if (isEnabled) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
            val port = sharedPreferences.getInt("http_server_port", 8000)
            binding.serverStatusText.text = "服务器已启用"
            binding.serverAddressText.text = "访问地址: http://$ipAddress:$port/heartrate"
        } else {
            binding.serverStatusText.text = "服务器已禁用"
            binding.serverAddressText.text = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}