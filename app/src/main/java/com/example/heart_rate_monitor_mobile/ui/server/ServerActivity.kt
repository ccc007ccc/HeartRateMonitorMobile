package com.example.heart_rate_monitor_mobile.ui.server

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
        when (key) {
            "http_server_enabled", "http_server_port",
            "websocket_server_enabled", "websocket_server_port" -> {
                updateServerStatusUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
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
        // HTTP Server
        val isHttpServerEnabled = sharedPreferences.getBoolean("http_server_enabled", false)
        val httpPort = sharedPreferences.getInt("http_server_port", 8000)
        binding.serverSwitch.isChecked = isHttpServerEnabled
        binding.portEditText.setText(httpPort.toString())
        binding.portEditText.isEnabled = !isHttpServerEnabled

        binding.serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("http_server_enabled", isChecked).apply()
            binding.portEditText.isEnabled = !isChecked
        }

        binding.portEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newPort = v.text.toString().toIntOrNull() ?: 8000
                sharedPreferences.edit().putInt("http_server_port", newPort).apply()
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // WebSocket Server
        val isWebSocketEnabled = sharedPreferences.getBoolean("websocket_server_enabled", false)
        val websocketPort = sharedPreferences.getInt("websocket_server_port", 8001)
        binding.websocketSwitch.isChecked = isWebSocketEnabled
        binding.websocketPortEditText.setText(websocketPort.toString())
        binding.websocketPortEditText.isEnabled = !isWebSocketEnabled

        binding.websocketSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("websocket_server_enabled", isChecked).apply()
            binding.websocketPortEditText.isEnabled = !isChecked
        }

        binding.websocketPortEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newPort = v.text.toString().toIntOrNull() ?: 8001
                sharedPreferences.edit().putInt("websocket_server_port", newPort).apply()
                v.clearFocus()
                true
            } else {
                false
            }
        }
    }

    // 更新UI，显示服务器的当前状态和访问地址
    private fun updateServerStatusUI() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)

        // HTTP Server Status
        val isHttpEnabled = sharedPreferences.getBoolean("http_server_enabled", false)
        if (isHttpEnabled) {
            val port = sharedPreferences.getInt("http_server_port", 8000)
            binding.serverStatusText.text = "HTTP 服务器已启用"
            binding.serverAddressText.text = "访问地址: http://$ipAddress:$port/heartrate"
        } else {
            binding.serverStatusText.text = "HTTP 服务器已禁用"
            binding.serverAddressText.text = ""
        }

        // WebSocket Server Status
        val isWebSocketEnabled = sharedPreferences.getBoolean("websocket_server_enabled", false)
        if (isWebSocketEnabled) {
            val port = sharedPreferences.getInt("websocket_server_port", 8001)
            binding.websocketStatusText.text = "WebSocket 服务器已启用"
            binding.websocketAddressText.text = "访问地址: ws://$ipAddress:$port"
        } else {
            binding.websocketStatusText.text = "WebSocket 服务器已禁用"
            binding.websocketAddressText.text = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
    }
}