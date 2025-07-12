package com.example.heart_rate_monitor_mobile

import android.Manifest
import android.animation.ValueAnimator
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.juul.kable.Advertisement
import com.permissionx.guolindev.PermissionX
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var localBroadcastManager: LocalBroadcastManager

    private var floatingService: FloatingWindowService? = null
    private var isServiceBound = false

    // 服务连接的回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingWindowService.LocalBinder
            floatingService = binder.getService()
            isServiceBound = true
            updateFloatingWindowUi(sharedPreferences.getBoolean("floating_window_enabled", false))
            // 服务绑定后，立即检查是否需要启动自动连接扫描
            checkAndStartAutoConnectScan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            floatingService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        Intent(this, FloatingWindowService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        setSupportActionBar(binding.toolbar)
        requestPermissions()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter()
        filter.addAction(FloatingWindowService.ACTION_UPDATE_BLE_STATE)
        filter.addAction(FloatingWindowService.ACTION_UPDATE_HEART_RATE)
        localBroadcastManager.registerReceiver(bleUpdateReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        updateFloatingWindowUi(sharedPreferences.getBoolean("floating_window_enabled", false))

        // **【BUG修复】**
        // 当应用返回前台时，与服务进行状态同步，确保UI正确性。
        if (isServiceBound && floatingService != null) {
            val isActuallyConnected = floatingService!!.isDeviceConnected()
            val isUiConnected = viewModel.appStatus.value == AppStatus.CONNECTED

            // 如果UI和服务状态不一致，以服务的状态为准
            if (isActuallyConnected != isUiConnected) {
                // 如果服务已断开但UI显示连接，则强制更新UI为断开状态
                if (!isActuallyConnected) {
                    viewModel.updateAppStatus(AppStatus.DISCONNECTED)
                    viewModel.updateStatusMessage("设备连接已断开")
                    viewModel.updateHeartRate(0)
                }
            }
        }
    }


    override fun onStop() {
        super.onStop()
        localBroadcastManager.unregisterReceiver(bleUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }


    /**
     * 检查并启动“扫描并自动连接”流程
     */
    private fun checkAndStartAutoConnectScan() {
        val isAutoConnectEnabled = sharedPreferences.getBoolean("auto_connect_enabled", false)
        val favoriteDeviceId = sharedPreferences.getString("favorite_device_id", null)

        if (isAutoConnectEnabled && favoriteDeviceId != null && isServiceBound) {
            viewModel.updateStatusMessage("正在扫描收藏的设备...")

            floatingService?.startScan(
                onScanResult = { advertisement ->
                    viewModel.addScanResult(advertisement)
                    // 如果找到了收藏的设备，立即发起连接
                    if (advertisement.identifier == favoriteDeviceId) {
                        floatingService?.connectToDevice(favoriteDeviceId)
                    }
                },
                onScanEnd = { found ->
                    // 扫描结束后，如果还没有进入连接状态（即没找到收藏的设备）
                    if (viewModel.appStatus.value != AppStatus.CONNECTING && viewModel.appStatus.value != AppStatus.CONNECTED) {
                        viewModel.updateStatusMessage("未找到收藏的设备")
                    }
                }
            )
        }
    }

    /**
     * 手动扫描流程
     */
    private fun startManualScan() {
        if (!isServiceBound) return
        viewModel.clearScanResults()
        floatingService?.startScan(
            onScanResult = { advertisement ->
                viewModel.addScanResult(advertisement)
            },
            onScanEnd = { found ->
                if (viewModel.appStatus.value == AppStatus.DISCONNECTED) {
                    if (!found) {
                        viewModel.updateStatusMessage("未找到任何设备")
                    } else {
                        viewModel.updateStatusMessage("扫描结束")
                    }
                }
            }
        )
    }

    // --- UI & 服务交互 ---

    private fun toggleFloatingWindow() {
        val shouldBeEnabled = !sharedPreferences.getBoolean("floating_window_enabled", false)
        if (shouldBeEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // 更新UI和配置
        with(sharedPreferences.edit()) {
            putBoolean("floating_window_enabled", shouldBeEnabled)
            apply()
        }
        updateFloatingWindowUi(shouldBeEnabled)
    }

    private fun updateFloatingWindowUi(isEnabled: Boolean) {
        if (!isServiceBound) return
        if (isEnabled) floatingService?.showWindow() else floatingService?.hideWindow()
        binding.floatingWindowButton.setImageResource(if (isEnabled) R.drawable.ic_floating_window_on else R.drawable.ic_floating_window_off)
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { advertisement ->
                floatingService?.connectToDevice(advertisement.identifier)
            },
            onFavoriteClick = { advertisement ->
                viewModel.toggleFavoriteDevice(advertisement)
                // 使用notifyDataSetChanged()来立即刷新星标状态
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

    private fun setupClickListeners() {
        binding.scanFab.setOnClickListener {
            startManualScan()
        }
        binding.disconnectButton.setOnClickListener {
            floatingService?.disconnectDevice()
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.floatingWindowButton.setOnClickListener {
            toggleFloatingWindow()
        }
    }

    private val bleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingWindowService.ACTION_UPDATE_BLE_STATE -> {
                    val message = intent.getStringExtra(FloatingWindowService.EXTRA_BLE_STATE_MESSAGE) ?: "未知状态"
                    viewModel.updateStatusMessage(message)
                    val stateType = intent.getStringExtra(FloatingWindowService.EXTRA_BLE_STATE_TYPE)
                    when (stateType) {
                        BleState.Scanning::class.java.name -> viewModel.updateAppStatus(AppStatus.SCANNING)
                        BleState.Connecting::class.java.name -> viewModel.updateAppStatus(AppStatus.CONNECTING)
                        BleState.Connected::class.java.name -> viewModel.updateAppStatus(AppStatus.CONNECTED)
                        else -> viewModel.updateAppStatus(AppStatus.DISCONNECTED)
                    }
                }
                FloatingWindowService.ACTION_UPDATE_HEART_RATE -> {
                    val rate = intent.getIntExtra(FloatingWindowService.EXTRA_HEART_RATE, 0)
                    viewModel.updateHeartRate(rate)
                }
            }
        }
    }

    private fun setupObservers() {
        viewModel.statusMessage.observe(this) { binding.statusTextView.text = it }
        viewModel.scanResults.observe(this) { results ->
            results?.let {
                val sortedResults = it.sortedWith(compareByDescending<Advertisement> { adv -> viewModel.isDeviceFavorite(adv.identifier) }.thenByDescending { adv -> adv.rssi })
                deviceAdapter.submitList(sortedResults)
            }
        }
        viewModel.heartRate.observe(this) { rate ->
            binding.heartRateTextView.text = if (rate > 0) "$rate" else "--"
            updateHeartbeatAnimation(rate)
        }
        viewModel.appStatus.observe(this) { status ->
            binding.statusProgressBar.visibility = if (status == AppStatus.SCANNING || status == AppStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.statusIcon.visibility = if (binding.statusProgressBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.scanFab.isEnabled = status == AppStatus.DISCONNECTED
            binding.scanFab.visibility = if (status == AppStatus.DISCONNECTED) View.VISIBLE else View.GONE
            val listVisible = status == AppStatus.SCANNING || status == AppStatus.DISCONNECTED
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
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
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