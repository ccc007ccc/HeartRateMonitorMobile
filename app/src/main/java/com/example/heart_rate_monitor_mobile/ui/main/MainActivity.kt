package com.example.heart_rate_monitor_mobile.ui.main

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.example.heart_rate_monitor_mobile.service.BleService
import com.example.heart_rate_monitor_mobile.service.FloatingWindowService
import com.example.heart_rate_monitor_mobile.ui.settings.SettingsActivity
import com.juul.kable.Advertisement
import com.permissionx.guolindev.PermissionX
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private var floatingService: FloatingWindowService? = null
    private var isFloatingServiceBound = false

    private val floatingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingWindowService.LocalBinder
            floatingService = binder.getService()
            isFloatingServiceBound = true
            updateFloatingWindowUi(sharedPreferences.getBoolean("floating_window_enabled", false))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            floatingService = null
            isFloatingServiceBound = false
        }
    }

    private val bleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            // Pass service instance to ViewModel
            viewModel.setBleService(binder.getService())
            setupObservers() // Observers depend on ViewModel having the service
            checkAndStartAutoConnectScan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Handle service disconnection if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // Start and bind to services
        startAndBindServices()

        requestPermissions()
        setupRecyclerView()
        setupClickListeners()
    }

    private fun startAndBindServices() {
        // Core BLE service
        Intent(this, BleService::class.java).also { intent ->
            startService(intent) // Use startService to keep it running
            bindService(intent, bleServiceConnection, Context.BIND_AUTO_CREATE)
        }
        // Floating Window UI service
        Intent(this, FloatingWindowService::class.java).also { intent ->
            bindService(intent, floatingServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        updateFloatingWindowUi(sharedPreferences.getBoolean("floating_window_enabled", false))
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(bleServiceConnection)
        if (isFloatingServiceBound) {
            unbindService(floatingServiceConnection)
        }
    }

    private fun checkAndStartAutoConnectScan() {
        val isAutoConnectEnabled = sharedPreferences.getBoolean("auto_connect_enabled", false)
        val favoriteDeviceId = sharedPreferences.getString("favorite_device_id", null)

        if (isAutoConnectEnabled && favoriteDeviceId != null) {
            // 【关键修改】调用新的自动扫描连接方法，而不是直接连接
            viewModel.startAutoConnectScan(favoriteDeviceId)
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { advertisement -> viewModel.connectToDevice(advertisement.identifier) },
            onFavoriteClick = { advertisement ->
                viewModel.toggleFavoriteDevice(advertisement)
                deviceAdapter.notifyDataSetChanged() // simple refresh for star
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
        binding.scanFab.setOnClickListener { viewModel.startScan() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnectDevice() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.floatingWindowButton.setOnClickListener { toggleFloatingWindow() }
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
            // 【关键修改】在连接中（包括自动连接）也显示列表
            val listVisible = status == AppStatus.SCANNING || status == AppStatus.DISCONNECTED || status == AppStatus.CONNECTING
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

    // ... [ All other methods like permission requests, animations, and floating window toggling remain largely the same ]
    private fun toggleFloatingWindow() {
        val shouldBeEnabled = !sharedPreferences.getBoolean("floating_window_enabled", false)
        if (shouldBeEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        with(sharedPreferences.edit()) {
            putBoolean("floating_window_enabled", shouldBeEnabled)
            apply()
        }
        updateFloatingWindowUi(shouldBeEnabled)
    }

    private fun updateFloatingWindowUi(isEnabled: Boolean) {
        if (!isFloatingServiceBound) return
        if (isEnabled) floatingService?.showWindow() else floatingService?.hideWindow()
        binding.floatingWindowButton.setImageResource(if (isEnabled) R.drawable.ic_floating_window_on else R.drawable.ic_floating_window_off)
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
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        PermissionX.init(this)
            .permissions(permissionsToRequest)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(deniedList, "App requires these permissions to find and connect to your heart rate monitor.", "OK", "Cancel")
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(deniedList, "You need to grant these permissions manually in settings.", "OK", "Cancel")
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    binding.statusTextView.text = "Some permissions were denied. The app may not work correctly!"
                }
            }
    }
}