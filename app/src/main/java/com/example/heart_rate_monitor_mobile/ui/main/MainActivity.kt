package com.example.heart_rate_monitor_mobile.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.example.heart_rate_monitor_mobile.service.BleService
import com.example.heart_rate_monitor_mobile.service.FloatingWindowService
import com.example.heart_rate_monitor_mobile.service.overlay.HeartbeatAnimator
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.ui.history.HistoryActivity
import com.example.heart_rate_monitor_mobile.ui.settings.SettingsActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var realtimeChart: LineChart

    /**
     * 记录 MainActivity 创建时莫奈取色的开关状态。
     * DynamicColors 的 overlay 仅在 Activity 创建时应用，onResume 不会重评。
     * 若用户在设置页切换了莫奈开关，返回首页时需重建 MainActivity 才能应用/移除 overlay。
     */
    private var monetEnabledAtCreate = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onAllPermissionsGranted()
        } else {
            binding.statusTextView.text = getString(R.string.main_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式系统栏：顶部状态栏内边距应用到 AppBarLayout，底部手势条内边距应用到 bottomNavContainer
        // 消费 systemBars 内边距，避免 Material3 BottomNavigationView 二次应用导致底部留白过高
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNavContainer.setPadding(0, 0, 0, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 状态栏/导航栏图标根据 colorSurface 亮度自适应（支持莫奈取色动态变化）
        EdgeToEdgeUtils.adaptSystemBarIcons(this, binding.appBar)

        realtimeChart = binding.realtimeChart
        setupRealtimeChart()

        monetEnabledAtCreate = container.settings.settings.value.general.monetColorEnabled

        setupRecyclerView()
        setupClickListeners()
        setupObservers()

        // 前台保活服务无条件启动（与旧版一致）：即使权限被拒，
        // 服务器/通知等能力仍可用，BLE 相关操作各自做权限失败处理
        startService(Intent(this, BleService::class.java))
        requestPermissions()

        if (container.settings.settings.value.floating.enabled) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 莫奈取色状态若在离开首页期间被切换，需重启 MainActivity 以应用/移除 DynamicColors overlay。
        // 使用 startActivity + finish 而非 recreate()：后者在新实例上 setDecorFitsSystemWindows(false)
        // 可能未及时生效，导致底部导航栏 edge-to-edge 失效、系统手势条无法沉浸。
        val monetEnabledNow = container.settings.settings.value.general.monetColorEnabled
        if (monetEnabledNow != monetEnabledAtCreate) {
            monetEnabledAtCreate = monetEnabledNow
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
            return
        }
        updateFloatingWindowButton(container.settings.settings.value.floating.enabled)
        updateSpeedUiVisibility()
        updateUiByStatus(viewModel.appStatus.value)
        val history = viewModel.chartHistory
        if (history.isNotEmpty()) {
            updateChart(history)
        }
    }

    private fun onAllPermissionsGranted() {
        viewModel.onLocationPermissionGranted()
        viewModel.autoConnectIfEnabled()
    }

    private fun requestPermissions() {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onAllPermissionsGranted()
            return
        }
        if (missing.any { shouldShowRequestPermissionRationale(it) }) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_permission_title)
                .setMessage(R.string.main_permission_message)
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    binding.statusTextView.text = getString(R.string.main_permission_denied)
                }
                .setPositiveButton(R.string.main_permission_grant) { _, _ ->
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { advertisement -> viewModel.connectToDevice(advertisement.identifier) },
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

    private fun setupClickListeners() {
        binding.scanButton.setOnClickListener { viewModel.startScan() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnectDevice() }
        binding.floatingWindowButton.setOnClickListener { toggleFloatingWindow() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                R.id.nav_home -> true
                else -> false
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.statusMessage.collect { binding.statusTextView.text = it }
                }
                launch {
                    viewModel.scanResults.collect { deviceAdapter.submitList(it) }
                }
                launch {
                    viewModel.heartRate.collect { rate ->
                        binding.heartRateTextView.text = if (rate > 0) "$rate" else "--"
                        updateHeartbeatAnimation(rate)
                    }
                }
                launch {
                    viewModel.speed.collect { speed ->
                        binding.speedTextView.text = String.format(Locale.US, "%.1f", speed)
                    }
                }
                launch {
                    viewModel.newChartEntry.collect { entry ->
                        if (container.settings.settings.value.general.historyRecordingEnabled &&
                            viewModel.appStatus.value == AppStatus.CONNECTED
                        ) {
                            addChartEntry(entry)
                        }
                    }
                }
                launch {
                    viewModel.appStatus.collect { updateUiByStatus(it) }
                }
                launch {
                    viewModel.sampleRate.collect { rate ->
                        if (rate > 0f && viewModel.appStatus.value == AppStatus.CONNECTED) {
                            binding.sampleRateText.visibility = View.VISIBLE
                            binding.sampleRateText.text =
                                getString(R.string.main_sample_rate, String.format(Locale.US, "%.1f", rate))
                        } else {
                            binding.sampleRateText.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.uiEvents.collect { event ->
                        when (event) {
                            is MainUiEvent.ShowToast ->
                                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    container.settings.flowOf { it.floating.enabled }
                        .collect { updateFloatingWindowButton(it) }
                }
            }
        }
    }

    // ---------- 实时图表 ----------

    private fun setupRealtimeChart() {
        // 深浅色主题下都用解析后的主题色（旧版硬编码 *_light 色值，深色模式几乎不可读）
        val axisTextColor = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        realtimeChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setDrawGridBackground(false)
            setPinchZoom(true)
            setBackgroundColor(Color.TRANSPARENT)

            data = LineData()
            legend.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = axisTextColor
            xAxis.setDrawGridLines(false)
            xAxis.setAvoidFirstLastClipping(true)
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val minutes = TimeUnit.SECONDS.toMinutes(value.toLong())
                    val seconds = value.toLong() % 60
                    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
            }

            axisLeft.textColor = axisTextColor
            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
        }
    }

    private fun updateChart(entries: List<Entry>) {
        val data = realtimeChart.data ?: return
        var set = data.getDataSetByIndex(0) as? LineDataSet
        if (set == null) {
            set = createChartDataSet()
            data.addDataSet(set)
        }
        set.values = entries
        data.notifyDataChanged()
        realtimeChart.notifyDataSetChanged()
    }

    private fun addChartEntry(entry: Entry) {
        val data = realtimeChart.data ?: return
        var set = data.getDataSetByIndex(0)
        if (set == null) {
            set = createChartDataSet()
            data.addDataSet(set)
        }
        data.addEntry(entry, 0)
        data.notifyDataChanged()

        realtimeChart.notifyDataSetChanged()
        realtimeChart.setVisibleXRangeMaximum(300f)
        realtimeChart.moveViewToX(data.entryCount.toFloat())
    }

    private fun createChartDataSet(): LineDataSet {
        val primary = MaterialColors.getColor(
            binding.root, androidx.appcompat.R.attr.colorPrimary
        )
        val set = LineDataSet(null, "Heart Rate")
        set.mode = LineDataSet.Mode.LINEAR
        set.color = primary
        set.lineWidth = 1.5f
        set.setDrawCircles(true)
        set.circleRadius = 2f
        set.setCircleColor(primary)
        set.setDrawValues(false)
        set.setDrawFilled(true)
        set.fillDrawable = ContextCompat.getDrawable(this, R.drawable.background_heart_rate_connected)
        set.fillAlpha = 85
        return set
    }

    private fun clearChart() {
        realtimeChart.data?.clearValues()
        realtimeChart.notifyDataSetChanged()
        realtimeChart.invalidate()
    }

    // ---------- 状态驱动的 UI ----------

    private fun updateSpeedUiVisibility() {
        val isSpeedEnabled = container.settings.settings.value.general.speedDisplayEnabled
        binding.speedCard.visibility =
            if (isSpeedEnabled && viewModel.appStatus.value == AppStatus.CONNECTED) View.VISIBLE else View.GONE
    }

    private fun updateUiByStatus(status: AppStatus) {
        binding.statusProgressBar.visibility =
            if (status == AppStatus.SCANNING || status == AppStatus.CONNECTING) View.VISIBLE else View.GONE
        binding.statusIcon.visibility =
            if (binding.statusProgressBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        binding.scanButton.isEnabled = status == AppStatus.DISCONNECTED
        binding.scanButton.alpha = if (status == AppStatus.DISCONNECTED) 1f else 0.4f

        val isConnected = status == AppStatus.CONNECTED
        val isHistoryEnabled = container.settings.settings.value.general.historyRecordingEnabled

        binding.realtimeChart.visibility = if (isConnected && isHistoryEnabled) View.VISIBLE else View.GONE
        binding.devicesRecyclerView.visibility = if (isConnected) View.GONE else View.VISIBLE
        binding.deviceListTitle.visibility = if (isConnected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (isConnected) View.VISIBLE else View.GONE

        updateSpeedUiVisibility()

        when (status) {
            AppStatus.CONNECTED -> {
                binding.heartRateCard.background =
                    ContextCompat.getDrawable(this, R.drawable.background_heart_rate_connected)
                binding.bgHeartIcon.alpha = 0.25f
                binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_connected)
                binding.statusIcon.setColorFilter(
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
                )
            }
            else -> {
                binding.heartRateCard.background =
                    ContextCompat.getDrawable(this, R.drawable.background_heart_rate_disconnected)
                binding.bgHeartIcon.alpha = 0.15f
                binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_disabled)
                binding.statusIcon.setColorFilter(
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
                )
                updateHeartbeatAnimation(0)
                clearChart()
            }
        }
    }

    // ---------- 悬浮窗开关 ----------

    private fun toggleFloatingWindow() {
        val shouldBeEnabled = !container.settings.settings.value.floating.enabled
        if (shouldBeEnabled && !Settings.canDrawOverlays(this)) {
            suppressHideForExternalLaunch = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        // 先挂起等设置写入完成再启动服务，避免服务首个收集值仍是 false
        lifecycleScope.launch {
            container.settings.set(SettingsKeys.FLOATING_WINDOW_ENABLED, shouldBeEnabled)
            if (shouldBeEnabled) {
                startService(Intent(this@MainActivity, FloatingWindowService::class.java))
            }
        }
    }

    private fun updateFloatingWindowButton(isEnabled: Boolean) {
        // 颜色全部从主题解析：开启莫奈取色时随动态色变化（旧实现硬编码 primary_light/#E0E0E0）
        binding.floatingWindowButton.setIconResource(
            if (isEnabled) R.drawable.ic_floating_window_on else R.drawable.ic_floating_window_off
        )
        val backgroundColor = MaterialColors.getColor(
            binding.root,
            if (isEnabled) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        val iconColor = MaterialColors.getColor(
            binding.root,
            if (isEnabled) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        binding.floatingWindowButton.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        binding.floatingWindowButton.iconTint = ColorStateList.valueOf(iconColor)
    }

    // ---------- 心跳动画（共享实现见 service/overlay/HeartbeatAnimator） ----------

    private val heartbeatAnimator by lazy { HeartbeatAnimator(binding.bgHeartIcon, maxScale = 1.3f) }

    private fun updateHeartbeatAnimation(bpm: Int) {
        heartbeatAnimator.update(
            bpm,
            container.settings.settings.value.general.heartbeatAnimationEnabled &&
                viewModel.appStatus.value == AppStatus.CONNECTED,
        )
    }
}
