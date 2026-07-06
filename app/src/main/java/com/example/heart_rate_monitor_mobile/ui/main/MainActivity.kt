package com.example.heart_rate_monitor_mobile.ui.main

import android.Manifest
import android.animation.ValueAnimator
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ble.BleState
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.example.heart_rate_monitor_mobile.service.BleService
import com.example.heart_rate_monitor_mobile.service.FloatingWindowService
import com.example.heart_rate_monitor_mobile.ui.history.HistoryActivity
import com.example.heart_rate_monitor_mobile.ui.settings.SettingsActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.juul.kable.Advertisement
import com.permissionx.guolindev.PermissionX
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var sharedPreferences: SharedPreferences

    /**
     * 记录 MainActivity 创建时莫奈取色的开关状态。
     * DynamicColors 的 overlay 仅在 Activity 创建时应用，onResume 不会重评。
     * 若用户在设置页切换了莫奈开关，返回首页时需重建 MainActivity 才能应用/移除 overlay。
     */
    private var monetEnabledAtCreate = true

    private var floatingService: FloatingWindowService? = null
    private var isFloatingServiceBound = false

    private var bleService: BleService? = null

    private lateinit var realtimeChart: LineChart
    private lateinit var db: AppDatabase

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
            bleService = binder.getService()
            viewModel.setBleService(bleService!!)
            setupObservers()
            checkAndStartAutoConnectScan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
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

        db = AppDatabase.getDatabase(this)
        cleanupOpenSessions()

        realtimeChart = binding.realtimeChart
        setupRealtimeChart()

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        monetEnabledAtCreate = sharedPreferences.getBoolean("monet_color_enabled", true)

        startAndBindServices()

        requestPermissions()
        setupRecyclerView()
        setupClickListeners()
    }

    private fun cleanupOpenSessions() {
        lifecycleScope.launch {
            val openSessions = db.heartRateDao().getOpenSessions()
            for (session in openSessions) {
                val lastTimestamp = db.heartRateDao().getLastRecordTimestampForSession(session.id)
                db.heartRateDao().endSession(session.id, lastTimestamp ?: session.startTime)
            }
        }
    }

    private fun startAndBindServices() {
        Intent(this, BleService::class.java).also { intent ->
            startService(intent)
            bindService(intent, bleServiceConnection, Context.BIND_AUTO_CREATE)
        }
        Intent(this, FloatingWindowService::class.java).also { intent ->
            bindService(intent, floatingServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // 莫奈取色状态若在离开首页期间被切换，需重启 MainActivity 以应用/移除 DynamicColors overlay。
        // 使用 startActivity + finish 而非 recreate()：后者在新实例上 setDecorFitsSystemWindows(false)
        // 可能未及时生效，导致底部导航栏 edge-to-edge 失效、系统手势条无法沉浸。
        val monetEnabledNow = sharedPreferences.getBoolean("monet_color_enabled", true)
        if (monetEnabledNow != monetEnabledAtCreate) {
            monetEnabledAtCreate = monetEnabledNow
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            overridePendingTransition(0, 0)
            finish()
            return
        }
        updateFloatingWindowUi(sharedPreferences.getBoolean("floating_window_enabled", false))
        updateSpeedUiVisibility()

        viewModel.appStatus.value?.let { updateUiByStatus(it) }
        val history = viewModel.chartHistory
        if (history.isNotEmpty()) {
            updateChart(history)
        }
    }

    private fun updateSpeedUiVisibility() {
        val isSpeedEnabled = sharedPreferences.getBoolean("speed_display_enabled", false)
        binding.speedCard.visibility = if (isSpeedEnabled && viewModel.appStatus.value == AppStatus.CONNECTED) View.VISIBLE else View.GONE
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
            viewModel.startAutoConnectScan(favoriteDeviceId)
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
        binding.historyCard.setOnClickListener {
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

    private fun setupRealtimeChart() {
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
            xAxis.textColor = ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant_light)
            xAxis.setDrawGridLines(false)
            xAxis.setAvoidFirstLastClipping(true)
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val minutes = TimeUnit.SECONDS.toMinutes(value.toLong())
                    val seconds = value.toLong() % 60
                    return String.format("%02d:%02d", minutes, seconds)
                }
            }

            axisLeft.textColor = ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant_light)
            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
        }
    }

    private fun updateChart(entries: List<Entry>) {
        val data = realtimeChart.data
        if (data != null) {
            var set = data.getDataSetByIndex(0) as? LineDataSet
            if (set == null) {
                set = createChartDataSet()
                data.addDataSet(set)
            }
            set.values = entries
            data.notifyDataChanged()
            realtimeChart.notifyDataSetChanged()
        }
    }

    private fun addChartEntry(entry: Entry) {
        val data = realtimeChart.data
        if (data != null) {
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
    }

    private fun createChartDataSet(): LineDataSet {
        val set = LineDataSet(null, "Heart Rate")
        set.mode = LineDataSet.Mode.LINEAR
        set.color = ContextCompat.getColor(this, R.color.primary_light)
        set.lineWidth = 1.5f
        set.setDrawCircles(true)
        set.circleRadius = 2f
        set.setCircleColor(ContextCompat.getColor(this, R.color.primary_light))

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

        viewModel.speed.observe(this) { speed ->
            binding.speedTextView.text = String.format("%.1f", speed)
        }

        viewModel.newChartEntry.observe(this) { entry ->
            val isHistoryEnabled = sharedPreferences.getBoolean("history_recording_enabled", false)
            if (isHistoryEnabled && viewModel.appStatus.value == AppStatus.CONNECTED) {
                addChartEntry(entry)
            }
        }

        viewModel.appStatus.observe(this) { status ->
            updateUiByStatus(status)
        }

        var previousBleState: BleState? = null
        lifecycleScope.launch {
            bleService?.bleState?.collect { state ->
                when (state) {
                    is BleState.Connected -> showToast("已连接")
                    is BleState.AutoReconnecting -> showToast("连接已断开，正在尝试自动重连...")
                    is BleState.ScanFailed -> {
                        if (previousBleState is BleState.AutoReconnecting) {
                            showToast("重连失败")
                        }
                    }
                    else -> { /* 在其他状态下不显示Toast */ }
                }
                previousBleState = state
            }
        }
    }

    private fun updateUiByStatus(status: AppStatus) {
        binding.statusProgressBar.visibility = if (status == AppStatus.SCANNING || status == AppStatus.CONNECTING) View.VISIBLE else View.GONE
        binding.statusIcon.visibility = if (binding.statusProgressBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        binding.scanButton.isEnabled = status == AppStatus.DISCONNECTED
        binding.scanButton.alpha = if (status == AppStatus.DISCONNECTED) 1f else 0.4f

        val isConnected = status == AppStatus.CONNECTED
        val isHistoryEnabled = sharedPreferences.getBoolean("history_recording_enabled", false)

        binding.realtimeChart.visibility = if (isConnected && isHistoryEnabled) View.VISIBLE else View.GONE
        binding.devicesRecyclerView.visibility = if (isConnected) View.GONE else View.VISIBLE
        binding.deviceListTitle.visibility = if (isConnected) View.GONE else View.VISIBLE

        binding.disconnectButton.visibility = if (isConnected) View.VISIBLE else View.GONE

        updateSpeedUiVisibility()

        when (status) {
            AppStatus.CONNECTED -> {
                binding.heartRateCard.background = ContextCompat.getDrawable(this, R.drawable.background_heart_rate_connected)
                binding.bgHeartIcon.text = "❤️"
                binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_connected)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_light))
            }
            else -> {
                binding.heartRateCard.background = ContextCompat.getDrawable(this, R.drawable.background_heart_rate_disconnected)
                binding.bgHeartIcon.text = "💔"
                binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_disabled)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.red_error))
                updateHeartbeatAnimation(0)
                clearChart()
            }
        }
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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

        // 【关键修复】更新图标和背景颜色，确保开关状态视觉明显
        binding.floatingWindowButton.setImageResource(if (isEnabled) R.drawable.ic_floating_window_on else R.drawable.ic_floating_window_off)

        val activeColor = ContextCompat.getColor(this, R.color.primary_light)
        val inactiveColor = Color.parseColor("#E0E0E0") // 浅灰色
        val iconColor = if(isEnabled) Color.WHITE else Color.BLACK

        binding.floatingWindowButton.backgroundTintList = ColorStateList.valueOf(if (isEnabled) activeColor else inactiveColor)
        binding.floatingWindowButton.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()

    private fun updateHeartbeatAnimation(bpm: Int) {
        val heartIcon = binding.bgHeartIcon
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

        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        PermissionX.init(this)
            .permissions(permissionsToRequest)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(deniedList, "App requires these permissions to find devices and calculate speed.", "OK", "Cancel")
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(deniedList, "You need to grant these permissions manually in settings.", "OK", "Cancel")
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    binding.statusTextView.text = "Some permissions were denied. Features may be limited!"
                } else {
                    val intent = Intent(this, BleService::class.java)
                    startService(intent)
                }
            }
    }
}