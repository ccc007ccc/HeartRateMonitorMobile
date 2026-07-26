package com.example.heart_rate_monitor_mobile.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.settings.ServerSettings
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivityServerBinding
import com.example.heart_rate_monitor_mobile.service.server.ServerController
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import kotlinx.coroutines.launch

class ServerActivity : BaseActivity() {

    private lateinit var binding: ActivityServerBinding
    private val settings get() = container.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        setupToolbar()
        setupViews()
        observeSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupViews() {
        val server = settings.settings.value.server

        // HTTP Server
        binding.serverSwitch.isChecked = server.httpEnabled
        binding.portEditText.setText(server.httpPort.toString())
        binding.serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.HTTP_SERVER_ENABLED, isChecked)
        }
        binding.portEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newPort = v.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8000
                settings.setAsync(SettingsKeys.HTTP_SERVER_PORT, newPort)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // WebSocket Server
        binding.websocketSwitch.isChecked = server.webSocketEnabled
        binding.websocketPortEditText.setText(server.webSocketPort.toString())
        binding.websocketSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAsync(SettingsKeys.WEBSOCKET_SERVER_ENABLED, isChecked)
        }
        binding.websocketPortEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newPort = v.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8001
                settings.setAsync(SettingsKeys.WEBSOCKET_SERVER_PORT, newPort)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // 访问安全
        binding.allowLanSwitch.isChecked = server.allowLan
        binding.allowLanSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                if (isChecked && settings.settings.value.server.authToken.isEmpty()) {
                    settings.set(SettingsKeys.SERVER_AUTH_TOKEN, ServerController.generateToken())
                }
                settings.set(SettingsKeys.SERVER_ALLOW_LAN, isChecked)
            }
        }
        binding.copyTokenButton.setOnClickListener {
            val token = settings.settings.value.server.authToken
            if (token.isEmpty()) return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("server token", token))
            Toast.makeText(this, R.string.server_token_copied, Toast.LENGTH_SHORT).show()
        }
        binding.resetTokenButton.setOnClickListener {
            settings.setAsync(SettingsKeys.SERVER_AUTH_TOKEN, ServerController.generateToken())
            Toast.makeText(this, R.string.server_token_reset, Toast.LENGTH_SHORT).show()
        }
    }

    /** 设置流驱动 UI 实时刷新（替代 OnSharedPreferenceChangeListener） */
    private fun observeSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.flowOf { it.server }.collect { updateServerStatusUI(it) }
            }
        }
    }

    /** 更新 UI，显示服务器的当前状态和访问地址 */
    private fun updateServerStatusUI(server: ServerSettings) {
        // 端口输入框在服务器运行期间锁定
        binding.portEditText.isEnabled = !server.httpEnabled
        binding.websocketPortEditText.isEnabled = !server.webSocketEnabled

        // Token 区域仅在开启局域网访问时展示
        binding.tokenContainer.visibility = if (server.allowLan) View.VISIBLE else View.GONE
        binding.tokenText.text = server.authToken.ifEmpty { getString(R.string.server_token_generating) }

        val host = if (server.allowLan) lanIpAddress() else "127.0.0.1"
        val tokenSuffix = if (server.allowLan && server.authToken.isNotEmpty()) {
            "?token=${server.authToken}"
        } else {
            ""
        }

        if (server.httpEnabled) {
            binding.serverStatusText.text = getString(R.string.server_http_enabled)
            binding.serverAddressText.text = getString(
                R.string.server_address, "http://$host:${server.httpPort}/heartrate$tokenSuffix"
            )
        } else {
            binding.serverStatusText.text = getString(R.string.server_http_disabled)
            binding.serverAddressText.text = ""
        }

        if (server.webSocketEnabled) {
            binding.websocketStatusText.text = getString(R.string.server_ws_enabled)
            binding.websocketAddressText.text = getString(
                R.string.server_address, "ws://$host:${server.webSocketPort}/$tokenSuffix"
            )
        } else {
            binding.websocketStatusText.text = getString(R.string.server_ws_disabled)
            binding.websocketAddressText.text = ""
        }
    }

    @Suppress("DEPRECATION")
    private fun lanIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }
}
