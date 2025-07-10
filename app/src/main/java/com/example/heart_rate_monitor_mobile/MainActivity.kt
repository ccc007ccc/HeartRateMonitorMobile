// app/src/main/java/com/example/heart_rate_monitor_mobile/MainActivity.kt
package com.example.heart_rate_monitor_mobile

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.permissionx.guolindev.PermissionX
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private var isFloatingWindowOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // 设置 Toolbar
        setSupportActionBar(binding.toolbar)
        // 请求必要的权限
        requestPermissions()
        // 设置 RecyclerView
        setupRecyclerView()
        // 设置 LiveData 观察者
        setupObservers()
        // 设置点击监听器
        setupClickListeners()
        // 应用启动时检查悬浮窗状态**
        checkFloatingWindowOnStartup()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.appStatus.value == AppStatus.DISCONNECTED &&
            sharedPreferences.getBoolean("auto_connect_enabled", false)) {
            viewModel.startScan()
        }
        // 每次返回主界面时，都根据最新的状态更新悬浮窗按钮的图标
        updateFloatingWindowToggleState()
    }

    /**
     * 在应用启动时检查悬浮窗的设置。
     * 如果用户之前开启了悬浮窗，则在应用启动时自动打开它。
     */
    private fun checkFloatingWindowOnStartup() {
        val isEnabled = sharedPreferences.getBoolean("floating_window_enabled", false)
        if (isEnabled) {
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                // 如果有权限，直接启动服务
                startService(Intent(this, FloatingWindowService::class.java))
                isFloatingWindowOn = true
            }
            // 如果没有权限，在 onResume 中用户会看到正确的未开启状态，可以手动点击开启以请求权限
        }
        // 更新按钮状态
        updateFloatingWindowToggleState()
    }

    private fun updateFloatingWindowToggleState() {
        // 从 SharedPreferences 读取最新的悬浮窗状态
        isFloatingWindowOn = sharedPreferences.getBoolean("floating_window_enabled", false)
        if (isFloatingWindowOn) {
            binding.floatingWindowButton.setImageResource(R.drawable.ic_floating_window_on)
        } else {
            binding.floatingWindowButton.setImageResource(R.drawable.ic_floating_window_off)
        }
    }

    private fun toggleFloatingWindow() {
        // 检查悬浮窗权限
        if (!isFloatingWindowOn) {
            // 如果悬浮窗未开启，检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // 没有权限，则请求权限
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                // 有权限，直接开启
                enableFloatingWindow(true)
            }
        } else {
            // 如果悬浮窗已开启，则直接关闭
            enableFloatingWindow(false)
        }
    }

    private fun enableFloatingWindow(enable: Boolean) {
        if (enable) {
            startService(Intent(this, FloatingWindowService::class.java))
        } else {
            stopService(Intent(this, FloatingWindowService::class.java))
        }
        isFloatingWindowOn = enable
        // 保存状态到 SharedPreferences
        with(sharedPreferences.edit()) {
            putBoolean("floating_window_enabled", enable)
            apply()
        }
        // 更新按钮图标
        updateFloatingWindowToggleState()
    }


    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { advertisement -> viewModel.connectToDevice(advertisement) },
            onFavoriteClick = { advertisement ->
                viewModel.toggleFavoriteDevice(advertisement)
                deviceAdapter.notifyDataSetChanged()
            },
            isFavorite = { identifier -> viewModel.isDeviceFavorite(identifier) }
        )
        binding.devicesRecyclerView.apply {
            adapter = deviceAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
        }
    }

    private fun setupObservers() {
        viewModel.statusMessage.observe(this) { binding.statusTextView.text = it }

        viewModel.scanResults.observe(this) { results ->
            val sortedResults = results.sortedWith(
                compareByDescending<com.juul.kable.Advertisement> { viewModel.isDeviceFavorite(it.identifier) }
                    .thenByDescending { it.rssi }
            )
            deviceAdapter.submitList(sortedResults)
        }

        viewModel.heartRate.observe(this) { rate ->
            if (rate > 0) {
                binding.heartRateTextView.text = "$rate"
                updateHeartbeatAnimation(rate)
            } else {
                binding.heartRateTextView.text = "--"
                updateHeartbeatAnimation(0)
            }
            // 将心率广播给 Service
            val intent = Intent(FloatingWindowService.ACTION_UPDATE_HEART_RATE)
            intent.putExtra(FloatingWindowService.EXTRA_HEART_RATE, rate)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }

        viewModel.appStatus.observe(this) { status ->
            binding.statusProgressBar.visibility =
                if (status == AppStatus.SCANNING || status == AppStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.statusIcon.visibility =
                if (binding.statusProgressBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE

            binding.scanFab.isEnabled = status == AppStatus.DISCONNECTED
            binding.scanFab.visibility = if (status == AppStatus.DISCONNECTED) View.VISIBLE else View.GONE

            val listVisible = status == AppStatus.DISCONNECTED || status == AppStatus.SCANNING
            binding.devicesRecyclerView.visibility = if (listVisible) View.VISIBLE else View.GONE
            binding.deviceListTitle.visibility = if (listVisible) View.VISIBLE else View.GONE
            binding.disconnectButton.visibility = if (status == AppStatus.CONNECTED) View.VISIBLE else View.GONE

            when (status) {
                AppStatus.CONNECTED -> {
                    binding.heartRateCard.background = ContextCompat.getDrawable(this, R.drawable.background_heart_rate_connected)
                    binding.heartIcon.text = "❤️"
                    binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_connected)
                    binding.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_light))
                }
                else -> {
                    binding.heartRateCard.background = ContextCompat.getDrawable(this, R.drawable.background_heart_rate_disconnected)
                    binding.heartIcon.text = "💔"
                    binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_disabled)
                    binding.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.red_error))
                    updateHeartbeatAnimation(0)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.scanFab.setOnClickListener { viewModel.startScan() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnectDevice() }

        // 设置按钮的点击事件
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 悬浮窗按钮的点击事件
        binding.floatingWindowButton.setOnClickListener {
            toggleFloatingWindow()
        }
    }

    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()

    private fun updateHeartbeatAnimation(bpm: Int) {
        val heartIcon = binding.heartIcon
        val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true)

        if (isAnimationEnabled && bpm > 30 && viewModel.appStatus.value == AppStatus.CONNECTED) {
            val targetDuration = (60000f / bpm).toLong()

            if (heartRateAnimator == null || (currentDuration - targetDuration).absoluteValue > 50) {
                currentDuration = targetDuration
                heartRateAnimator?.cancel()

                heartRateAnimator = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
                    duration = currentDuration
                    interpolator = beatInterpolator
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART

                    addUpdateListener { animation ->
                        val scale = animation.animatedValue as Float
                        heartIcon.scaleX = scale
                        heartIcon.scaleY = scale
                    }
                    start()
                }
            }
        } else {
            heartRateAnimator?.cancel()
            heartRateAnimator = null
            currentDuration = 0L
            heartIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        PermissionX.init(this)
            .permissions(permissionsToRequest)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(deniedList, "应用需要这些权限才能发现并连接到您的心率监测设备。", "好的", "取消")
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(deniedList, "您需要手动在设置中允许这些权限。", "好的", "取消")
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    binding.statusTextView.text = "部分权限被拒绝，应用可能无法正常工作！"
                }
            }
    }
}