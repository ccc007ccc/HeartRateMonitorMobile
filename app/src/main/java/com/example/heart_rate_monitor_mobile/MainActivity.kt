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
import android.view.Menu
import android.view.MenuItem
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
    private var floatingWindowMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setSupportActionBar(binding.toolbar)
        requestPermissions()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.appStatus.value == AppStatus.DISCONNECTED &&
            sharedPreferences.getBoolean("auto_connect_enabled", false)) {
            viewModel.startScan()
        }
        updateFloatingWindowToggleState()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        floatingWindowMenuItem = menu?.findItem(R.id.action_floating_window)
        updateFloatingWindowToggleState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_floating_window -> {
                toggleFloatingWindow()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateFloatingWindowToggleState() {
        isFloatingWindowOn = sharedPreferences.getBoolean("floating_window_enabled", false)
        if (isFloatingWindowOn) {
            floatingWindowMenuItem?.icon = ContextCompat.getDrawable(this, R.drawable.ic_floating_window_on)
        } else {
            floatingWindowMenuItem?.icon = ContextCompat.getDrawable(this, R.drawable.ic_floating_window_off)
        }
    }

    private fun toggleFloatingWindow() {
        if (!isFloatingWindowOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                enableFloatingWindow(true)
            }
        } else {
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
        with(sharedPreferences.edit()) {
            putBoolean("floating_window_enabled", enable)
            apply()
        }
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