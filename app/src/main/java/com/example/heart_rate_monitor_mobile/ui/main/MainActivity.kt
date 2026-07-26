package com.example.heart_rate_monitor_mobile.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.example.heart_rate_monitor_mobile.databinding.SheetComparisonScanBinding
import com.example.heart_rate_monitor_mobile.service.BleService
import com.example.heart_rate_monitor_mobile.service.FloatingWindowService
import com.example.heart_rate_monitor_mobile.service.overlay.HeartbeatAnimator
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.ui.chart.HeartRateChartController
import com.example.heart_rate_monitor_mobile.ui.history.HistoryActivity
import com.example.heart_rate_monitor_mobile.ui.settings.SettingsActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var chartController: HeartRateChartController

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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNavContainer.setPadding(0, 0, 0, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 状态栏/导航栏图标根据 colorSurface 亮度自适应（支持莫奈取色动态变化）
        EdgeToEdgeUtils.adaptSystemBarIcons(this, binding.appBar)

        chartController = HeartRateChartController(binding.realtimeChart)

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
        rebuildChart()
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
        binding.addComparisonButton.setOnClickListener { showComparisonSheet() }
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
                    viewModel.newChartPoint.collect { point ->
                        if (isChartEnabled() && viewModel.appStatus.value == AppStatus.CONNECTED) {
                            chartController.appendPoint(point.seriesId, point.timestampMs, point.bpm)
                        }
                    }
                }
                launch {
                    viewModel.chartStructureChanged.collect { rebuildChart() }
                }
                launch {
                    viewModel.comparisonRows.collect { renderComparisonRows(it) }
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
                launch {
                    container.settings.flowOf { it.general.comparisonModeEnabled }
                        .collect { updateUiByStatus(viewModel.appStatus.value) }
                }
            }
        }
    }

    // ---------- 图表（共享控制器，多序列叠加） ----------

    private fun isComparisonMode(): Boolean =
        container.settings.settings.value.general.comparisonModeEnabled

    /** 对比模式下图表常驻；普通模式沿用 v2.0 行为（连接 + 开启历史记录才显示） */
    private fun isChartEnabled(): Boolean {
        val isConnected = viewModel.appStatus.value == AppStatus.CONNECTED
        return if (isComparisonMode()) {
            isConnected
        } else {
            isConnected && container.settings.settings.value.general.historyRecordingEnabled
        }
    }

    private fun rebuildChart() {
        val snapshot = viewModel.chartSnapshot()
        val rows = viewModel.comparisonRows.value
        val primaryLabel = viewModel.primaryDeviceName.value
            .ifEmpty { getString(R.string.comparison_primary_tag) }
        val series = buildList {
            add(
                HeartRateChartController.SeriesData(
                    id = MainViewModel.PRIMARY_SERIES_ID,
                    label = primaryLabel,
                    points = snapshot[MainViewModel.PRIMARY_SERIES_ID].orEmpty()
                        .map { HeartRateChartController.Point(it.timestampMs, it.bpm) },
                )
            )
            rows.forEach { row ->
                add(
                    HeartRateChartController.SeriesData(
                        id = row.id,
                        label = row.name,
                        points = snapshot[row.id].orEmpty()
                            .map { HeartRateChartController.Point(it.timestampMs, it.bpm) },
                    )
                )
            }
        }
        chartController.setSeries(series)
    }

    // ---------- 参赛设备列表 ----------

    private fun renderComparisonRows(rows: List<MainViewModel.ComparisonRow>) {
        val containerView = binding.comparisonContainer
        containerView.removeAllViews()
        if (!isComparisonMode()) return

        // 首行：主设备
        if (viewModel.appStatus.value == AppStatus.CONNECTED) {
            containerView.addView(
                buildComparisonRow(
                    colorIndex = 0,
                    name = viewModel.primaryDeviceName.value
                        .ifEmpty { getString(R.string.comparison_primary_tag) },
                    metrics = getString(
                        R.string.comparison_metrics,
                        viewModel.heartRate.value,
                        String.format(Locale.US, "%.1f", viewModel.sampleRate.value),
                    ),
                    connected = true,
                    onRemove = null,
                    onClick = null,
                )
            )
        }
        rows.forEach { row ->
            val metrics = if (row.connected) {
                buildString {
                    append(
                        getString(
                            R.string.comparison_metrics,
                            row.bpm,
                            String.format(Locale.US, "%.1f", row.rate),
                        )
                    )
                    if (row.lastDiff != null || row.meanAbsDiff != null) {
                        append(" · ")
                        append(
                            getString(
                                R.string.comparison_delta_mae,
                                row.lastDiff?.let { String.format(Locale.US, "%+d", it) } ?: "--",
                                row.meanAbsDiff?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                            )
                        )
                    }
                }
            } else {
                getString(R.string.comparison_disconnected)
            }
            containerView.addView(
                buildComparisonRow(
                    colorIndex = row.colorIndex,
                    name = row.name,
                    metrics = metrics,
                    connected = row.connected,
                    onRemove = { viewModel.removeComparisonDevice(row.id) },
                    onClick = if (!row.connected) {
                        { viewModel.reconnectComparisonDevice(row.id, row.name) }
                    } else null,
                )
            )
        }
    }

    private fun buildComparisonRow(
        colorIndex: Int,
        name: String,
        metrics: String,
        connected: Boolean,
        onRemove: (() -> Unit)?,
        onClick: (() -> Unit)?,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            alpha = if (connected) 1f else 0.5f
            onClick?.let { handler -> setOnClickListener { handler() } }
        }
        row.addView(
            TextView(this).apply {
                text = "●"
                textSize = 14f
                setTextColor(chartController.colorForIndex(colorIndex))
                setPadding(0, 0, dp(8), 0)
            }
        )
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(
                    TextView(this@MainActivity).apply {
                        text = name
                        setTextColor(
                            MaterialColors.getColor(
                                binding.root, com.google.android.material.R.attr.colorOnSurface
                            )
                        )
                        textSize = 14f
                    }
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = metrics
                        setTextColor(
                            MaterialColors.getColor(
                                binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
                            )
                        )
                        textSize = 12f
                    }
                )
            }
        )
        if (onRemove != null) {
            row.addView(
                ImageButton(this).apply {
                    setImageResource(R.drawable.ic_delete)
                    setBackgroundResource(android.R.color.transparent)
                    imageTintList = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                    contentDescription = getString(R.string.comparison_remove)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    setOnClickListener { onRemove() }
                }
            )
        }
        return row
    }

    private var sheetCollectJob: Job? = null

    /** 底部扫描面板：独立扫描（不打断主连接），点一台连一台，可连续添加 */
    private fun showComparisonSheet() {
        val sheetBinding = SheetComparisonScanBinding.inflate(LayoutInflater.from(this))
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val sheetAdapter = DeviceAdapter(
            onDeviceClick = { advertisement -> viewModel.connectComparisonDevice(advertisement) },
            onFavoriteClick = { },
            isFavorite = { false },
        )
        sheetBinding.comparisonScanRecycler.adapter = sheetAdapter
        sheetBinding.comparisonScanRecycler.layoutManager = LinearLayoutManager(this)

        sheetCollectJob = lifecycleScope.launch {
            launch {
                viewModel.comparisonScanResults.collect { results ->
                    val excluded = viewModel.comparisonRows.value.map { it.id }.toSet()
                    sheetAdapter.submitList(results.filter { it.identifier !in excluded })
                }
            }
            launch {
                viewModel.comparisonScanning.collect { scanning ->
                    sheetBinding.scanProgress.isVisible = scanning
                }
            }
        }
        dialog.setOnDismissListener {
            sheetCollectJob?.cancel()
            sheetCollectJob = null
        }
        viewModel.startComparisonScan()
        dialog.show()
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

        binding.realtimeChart.visibility = if (isChartEnabled()) View.VISIBLE else View.GONE
        binding.comparisonSection.visibility =
            if (isComparisonMode() && isConnected) View.VISIBLE else View.GONE
        binding.devicesRecyclerView.visibility = if (isConnected) View.GONE else View.VISIBLE
        binding.deviceListTitle.visibility = if (isConnected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (isConnected) View.VISIBLE else View.GONE

        updateSpeedUiVisibility()
        renderComparisonRows(viewModel.comparisonRows.value)

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
                chartController.clear()
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
        // 颜色全部从主题解析：开启莫奈取色时随动态色变化
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
