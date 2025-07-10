package com.example.heart_rate_monitor_mobile

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import com.juul.kable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.NoSuchElementException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): FloatingWindowService = this@FloatingWindowService }
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingWindowBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var localBroadcastManager: LocalBroadcastManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bleManager: BleManager? = null
    private var connectedPeripheral: Peripheral? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null

    private var isWindowShown = false
    @Volatile
    private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()

    companion object {
        const val ACTION_UPDATE_BLE_STATE = "com.example.heart_rate_monitor_mobile.UPDATE_BLE_STATE"
        const val EXTRA_BLE_STATE_MESSAGE = "extra_ble_state_message"
        const val EXTRA_BLE_STATE_TYPE = "extra_ble_state_type"
        const val ACTION_UPDATE_HEART_RATE = "com.example.heart_rate_monitor_mobile.UPDATE_HEART_RATE"
        const val EXTRA_HEART_RATE = "extra_heart_rate"
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        isManuallyDisconnected = true
                        stopAllBleActivities()
                        broadcastBleState(BleState.Disconnected("蓝牙已关闭"))
                    }
                    BluetoothAdapter.STATE_ON -> {
                        isManuallyDisconnected = false
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ... 组件初始化 ...
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        bleManager = BleManager(applicationContext)
        val contextWithTheme = ContextThemeWrapper(this, R.style.Theme_HeartRateMonitorMobile)
        val inflater = LayoutInflater.from(contextWithTheme)
        binding = LayoutFloatingWindowBinding.inflate(inflater)
        initLayoutParams()
        setupTouchListener()
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务仅保持运行，不主动执行任何操作
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        stopAllBleActivities()
        serviceScope.cancel()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        unregisterReceiver(bluetoothStateReceiver)
    }

    // --- 公开给客户端的核心方法 ---

    fun startScan(durationMillis: Long = 15_000L, onScanResult: (Advertisement) -> Unit, onScanEnd: (found: Boolean) -> Unit) {
        if (!isScanning.compareAndSet(false, true)) return
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            var foundDevice = false
            try {
                broadcastBleState(BleState.Scanning)
                withTimeout(durationMillis) {
                    bleManager!!.scan().collect {
                        foundDevice = true
                        onScanResult(it)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // 正常超时
            } finally {
                withContext(NonCancellable) {
                    onScanEnd(foundDevice)
                    isScanning.set(false)
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        isScanning.set(false)
    }

    fun connectToDevice(identifier: String) {
        isManuallyDisconnected = false
        stopAllBleActivities() // **核心修复**: 连接前确保停止所有扫描和旧连接
        connectionJob = serviceScope.launch {
            performConnection(identifier)
        }
    }

    fun disconnectDevice() {
        isManuallyDisconnected = true
        stopAllBleActivities()
        broadcastBleState(BleState.Disconnected("已手动断开连接"))
    }

    private fun stopAllBleActivities() {
        scanJob?.cancel()
        connectionJob?.cancel()
        cleanupConnection()
    }

    // --- 连接与数据处理逻辑 ---

    private suspend fun performConnection(identifier: String) {
        try {
            // **核心修复**: 直接进入Connecting状态，不再需要先查找
            broadcastBleState(BleState.Connecting, "正在连接...")
            // 使用Kable的peripheral(identifier)直接创建实例，无需扫描
            val peripheral = serviceScope.peripheral(identifier) {
                // 可在此处配置连接参数
            }
            connectedPeripheral = peripheral

            // 更新提示词，包含设备名
            peripheral.name?.let { name ->
                broadcastBleState(BleState.Connecting, "正在连接 $name...")
            }

            serviceScope.launch { monitorConnection(peripheral) }
            withTimeout(10_000L) { peripheral.connect() }

            val successMessage = "已连接到 ${peripheral.name ?: "未知设备"}"
            broadcastBleState(BleState.Connected(successMessage))
            MainScope().launch { Toast.makeText(applicationContext, successMessage, Toast.LENGTH_SHORT).show() }

            observeHeartRateData(peripheral)
        } catch (e: Exception) {
            val finalState: BleState = when (e) {
                is TimeoutCancellationException -> BleState.Disconnected("连接超时")
                is CancellationException -> throw e
                else -> BleState.Disconnected("连接失败: ${e.message ?: "未知错误"}")
            }
            broadcastBleState(finalState)
            cleanupConnection()
        }
    }

    private suspend fun monitorConnection(peripheral: Peripheral) {
        try {
            peripheral.state.filterIsInstance<State.Disconnected>().first()
            cleanupConnection()
            // 如果不是手动断开，则广播“意外断开”
            if (!isManuallyDisconnected) {
                broadcastBleState(BleState.Disconnected("设备连接已断开"))
            }
        } catch (e: CancellationException) { /* Normal cancellation */ }
    }

    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        bleManager!!.observeHeartRate(peripheral)
            .catch { e -> if (e !is CancellationException) broadcastBleState(BleState.Disconnected("心率读取错误: ${e.message ?: "未知错误"}")) }
            .collect { rate ->
                updateHeartRateText(rate)
                updateHeartbeatAnimation(rate)
                broadcastHeartRate(rate)
            }
    }

    private fun cleanupConnection() {
        serviceScope.launch {
            val p = connectedPeripheral; connectedPeripheral = null
            if (p != null && (p.state.value is State.Connecting || p.state.value is State.Connected)) {
                try { p.disconnect() } catch (e: Exception) { /* Ignore */ }
            }
            updateHeartRateText(0)
            updateHeartbeatAnimation(0)
            broadcastHeartRate(0)
        }
    }

    // --- 广播与UI方法 ---
    private fun broadcastBleState(state: BleState, customMessage: String? = null) {
        val intent = Intent(ACTION_UPDATE_BLE_STATE).apply {
            putExtra(EXTRA_BLE_STATE_TYPE, state::class.java.name)
            putExtra(EXTRA_BLE_STATE_MESSAGE, customMessage ?: state.message)
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun broadcastHeartRate(rate: Int) {
        localBroadcastManager.sendBroadcast(Intent(ACTION_UPDATE_HEART_RATE).putExtra(EXTRA_HEART_RATE, rate))
    }

    // ... UI & Other Logic ...
    fun showWindow() { if (isWindowShown) return; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return; try { windowManager.addView(binding.root, layoutParams); isWindowShown = true; updateWindowAppearance() } catch (e: Exception) {} }
    fun hideWindow() { if (!isWindowShown) return; try { windowManager.removeView(binding.root); isWindowShown = false } catch (e: Exception) {} }
    private fun updateHeartRateText(rate: Int) { val text = if (rate > 0) "$rate" else "--"; MainScope().launch { binding.floatingBpmNumber.text = text } }
    private fun updateHeartbeatAnimation(bpm: Int) { MainScope().launch { val heartIcon = binding.floatingHeartIcon; val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true); if (isAnimationEnabled && bpm > 30) { val targetDuration = (60000f / bpm).toLong(); if (heartRateAnimator == null || (currentDuration - targetDuration).absoluteValue > 50) { currentDuration = targetDuration; heartRateAnimator?.cancel(); heartRateAnimator = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply { duration = currentDuration; interpolator = beatInterpolator; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; addUpdateListener { animation -> val scale = animation.animatedValue as Float; heartIcon.scaleX = scale; heartIcon.scaleY = scale }; start() } } } else { heartRateAnimator?.cancel(); heartRateAnimator = null; currentDuration = 0L; heartIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start() } } }
    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (isWindowShown) { updateWindowAppearance() } }
    private fun initLayoutParams() { val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE; layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 } }
    @SuppressLint("ClickableViewAccessibility") private fun setupTouchListener() { binding.root.setOnTouchListener { _, event -> when (event.action) { MotionEvent.ACTION_DOWN -> { initialX = layoutParams.x; initialY = layoutParams.y; initialTouchX = event.rawX; initialTouchY = event.rawY; true } MotionEvent.ACTION_MOVE -> { layoutParams.x = initialX + (event.rawX - initialTouchX).toInt(); layoutParams.y = initialY + (event.rawY - initialTouchY).toInt(); if (isWindowShown) windowManager.updateViewLayout(binding.root, layoutParams); true } else -> false } } }
    private fun updateWindowAppearance() { MainScope().launch { val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK); val bgColor = sharedPreferences.getInt("floating_bg_color", Color.WHITE); val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY); val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 100) / 100f; val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f; val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 16).toFloat(); val sizePercent = sharedPreferences.getInt("floating_size", 100); val iconSizePercent = sharedPreferences.getInt("floating_icon_size", 100); val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true); val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true); val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)); val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)); val scaleFactor = sizePercent / 100f; val iconScaleFactor = iconSizePercent / 100f; val baseIconSizeSp = 18f; val baseTextSizeSp = 16f; val baseBpmTextSizeSp = 12f; val basePaddingDp = 8f; val baseMarginDp = 4f; binding.floatingBpmText.visibility = if (isBpmTextEnabled) View.VISIBLE else View.GONE; binding.floatingHeartIcon.visibility = if (isHeartIconEnabled) View.VISIBLE else View.GONE; binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * iconScaleFactor); binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor); binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseBpmTextSizeSp * scaleFactor); binding.floatingHeartIcon.setTextColor(textColor); binding.floatingBpmNumber.setTextColor(textColor); binding.floatingBpmText.setTextColor(textColor); val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams; bpmNumberParams.marginStart = if (isHeartIconEnabled) dpToPx(baseMarginDp * scaleFactor) else 0; binding.floatingBpmNumber.layoutParams = bpmNumberParams; val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout; val paddingPx = dpToPx(basePaddingDp * scaleFactor); rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx); (binding.root as MaterialCardView).apply { setCardBackgroundColor(finalBgColor); radius = cornerRadius; setStrokeColor(finalBorderColor); strokeWidth = dpToPx(1f) } } }
    private fun dpToPx(dp: Float): Int { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt() }
}