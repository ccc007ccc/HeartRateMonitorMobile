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
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.heart_rate_monitor_mobile.databinding.LayoutFloatingWindowBinding
import com.google.android.material.card.MaterialCardView
import com.juul.kable.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
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
    private lateinit var webhookManager: WebhookManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bleManager: BleManager? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null

    // --- 状态管理 ---
    private var connectedPeripheral: Peripheral? = null
    private var httpServer: HttpServer? = null
    @Volatile private var currentHeartRate: Int = 0
    private fun isDeviceConnected(): Boolean = connectedPeripheral?.state?.value is State.Connected

    @Volatile private var isManuallyDisconnected = false
    private val isScanning = AtomicBoolean(false)
    private var isWindowShown = false
    private var heartRateAnimator: ValueAnimator? = null
    private var currentDuration: Long = 0L
    private val beatInterpolator = AccelerateDecelerateInterpolator()
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f


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
                    }
                    BluetoothAdapter.STATE_ON -> {
                        isManuallyDisconnected = false
                    }
                }
            }
        }
    }

    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "http_server_enabled" -> {
                if (prefs.getBoolean(key, false)) startHttpServer() else stopHttpServer()
            }
            "http_server_port" -> {
                if (prefs.getBoolean("http_server_enabled", false)) restartHttpServer()
            }
            "floating_window_enabled", "heartbeat_animation_enabled" -> {
                if (isWindowShown) updateWindowAppearance()
            }
            else -> if (isWindowShown) updateWindowAppearance()
        }
    }


    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        webhookManager = WebhookManager(applicationContext)
        bleManager = BleManager(applicationContext)
        val contextWithTheme = ContextThemeWrapper(this, R.style.Theme_HeartRateMonitorMobile)
        val inflater = LayoutInflater.from(contextWithTheme)
        binding = LayoutFloatingWindowBinding.inflate(inflater)

        initLayoutParams()
        setupTouchListener()
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        if (sharedPreferences.getBoolean("http_server_enabled", false)) {
            startHttpServer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        stopAllBleActivities()
        stopHttpServer()
        serviceScope.cancel()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        unregisterReceiver(bluetoothStateReceiver)
    }

    private fun stopAllBleActivities() {
        scanJob?.cancel()
        connectionJob?.cancel()
    }

    /**
     * 【最终修正版】连接逻辑
     */
    fun connectToDevice(identifier: String) {
        stopAllBleActivities()
        isManuallyDisconnected = false

        connectionJob = serviceScope.launch {
            var peripheral: Peripheral? = null
            try {
                // 1. 创建外设
                peripheral = serviceScope.peripheral(identifier)
                connectedPeripheral = peripheral

                broadcastBleState(BleState.Connecting, "准备连接...")

                // 2. 启动状态监听器，这是关键
                val stateMonitor = launch {
                    peripheral.state
                        // **【关键修复】** 过滤掉初始的 Disconnected(null) 状态
                        .filter { it !is State.Disconnected || it.status != null }
                        .collect { state ->
                            Log.d("BleManager", "Filtered Connection State Changed: $state")
                            when (state) {
                                is State.Connecting -> {
                                    val msg = peripheral.name?.let { "正在连接 $it..." } ?: "正在连接..."
                                    broadcastBleState(BleState.Connecting, msg)
                                }
                                is State.Connected -> {
                                    val msg = "已连接到 ${peripheral.name ?: "未知设备"}"
                                    broadcastBleState(BleState.Connected(msg))
                                    MainScope().launch { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
                                    // 仅在连接成功后，启动心率监听
                                    launch { observeHeartRateData(peripheral) }
                                }
                                is State.Disconnecting -> {
                                    broadcastBleState(BleState.Disconnected("正在断开..."))
                                }
                                is State.Disconnected -> {
                                    // 任何真实的断开事件都会使整个任务取消
                                    this@launch.cancel(CancellationException("Device disconnected with status: ${state.status}"))
                                }
                            }
                        }
                }

                // 3. 执行带超时的连接操作
                withTimeout(20_000L) {
                    peripheral.connect()
                }

                // 4. 等待状态监听器结束（即等待断开连接）
                stateMonitor.join()

            } catch (e: Exception) {
                // 捕获所有异常，包括超时、连接失败等
                when (e) {
                    is TimeoutCancellationException -> {
                        Log.w("BleManager", "Connection timed out for $identifier")
                        broadcastBleState(BleState.Disconnected("连接超时"))
                    }
                    is CancellationException -> {
                        // 这是由断开连接触发的，是正常流程
                        Log.d("BleManager", "Connection job cancelled: ${e.message}")
                    }
                    else -> {
                        Log.e("BleManager", "Connection to $identifier failed", e)
                        broadcastBleState(BleState.Disconnected("连接失败: ${e.message}"))
                    }
                }
            } finally {
                // 5. 【最终清理】无论如何都会执行
                withContext(NonCancellable) {
                    Log.d("BleManager", "Running final cleanup for $identifier")
                    cleanupConnection()
                }
            }
        }
    }


    private fun cleanupConnection() {
        // 这个函数现在只负责重置状态
        val message = if (isManuallyDisconnected) "已手动断开" else "设备连接已断开"
        broadcastBleState(BleState.Disconnected(message))

        connectedPeripheral = null
        currentHeartRate = 0

        MainScope().launch {
            updateHeartRateText(0)
            updateHeartbeatAnimation(0)
            broadcastHeartRate(0)
        }
    }

    private suspend fun observeHeartRateData(peripheral: Peripheral) {
        try {
            bleManager!!.observeHeartRate(peripheral)
                .collect { rate ->
                    if (rate != currentHeartRate) {
                        currentHeartRate = rate
                        updateHeartRateText(rate)
                        updateHeartbeatAnimation(rate)
                        broadcastHeartRate(rate)
                        webhookManager.sendAllEnabledWebhooks(rate)
                    }
                }
        } catch (e: Exception) {
            Log.w("BleManager", "Heart rate observation stopped or failed.", e)
        }
    }

    // --- HTTP服务器管理 ---
    private fun startHttpServer() {
        if (httpServer == null) {
            val port = sharedPreferences.getInt("http_server_port", 8000)
            httpServer = HttpServer(port)
            try {
                httpServer?.start()
            } catch (e: IOException) {
                Log.e("FloatingWindowService", "HTTP Server start failed", e)
            }
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
    }

    private fun restartHttpServer() {
        stopHttpServer()
        startHttpServer()
    }

    private inner class HttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession?): Response {
            if (session?.method == Method.GET && session.uri == "/heartrate") {
                val json = JSONObject()
                json.put("heart_rate", currentHeartRate)
                json.put("connected", isDeviceConnected())
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }


    // --- 现有方法（无重大逻辑修改） ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    fun disconnectDevice() {
        isManuallyDisconnected = true
        stopAllBleActivities()
    }
    fun startScan(durationMillis: Long = 15_000L, onScanResult: (Advertisement) -> Unit, onScanEnd: (found: Boolean) -> Unit) {
        if (!isScanning.compareAndSet(false, true)) return
        stopAllBleActivities()
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
            } finally {
                withContext(NonCancellable) {
                    val status = if(foundDevice) "扫描结束" else "未找到任何设备"
                    broadcastBleState(BleState.ScanFailed(status))
                    isScanning.set(false)
                    onScanEnd(foundDevice)
                }
            }
        }
    }
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
    fun showWindow() { if (isWindowShown) return; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return; try { windowManager.addView(binding.root, layoutParams); isWindowShown = true; updateWindowAppearance() } catch (e: Exception) {} }
    fun hideWindow() { if (!isWindowShown) return; try { windowManager.removeView(binding.root); isWindowShown = false } catch (e: Exception) {} }
    private fun updateHeartRateText(rate: Int) { MainScope().launch { binding.floatingBpmNumber.text = if (rate > 0) "$rate" else "--" } }
    private fun updateHeartbeatAnimation(bpm: Int) { MainScope().launch { val heartIcon = binding.floatingHeartIcon; val isAnimationEnabled = sharedPreferences.getBoolean("heartbeat_animation_enabled", true); if (isAnimationEnabled && bpm > 30 && isDeviceConnected()) { val targetDuration = (60000f / bpm).toLong(); if (heartRateAnimator == null || (currentDuration - targetDuration).absoluteValue > 50) { currentDuration = targetDuration; heartRateAnimator?.cancel(); heartRateAnimator = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply { duration = currentDuration; interpolator = beatInterpolator; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; addUpdateListener { animation -> val scale = animation.animatedValue as Float; heartIcon.scaleX = scale; heartIcon.scaleY = scale }; start() } } } else { heartRateAnimator?.cancel(); heartRateAnimator = null; currentDuration = 0L; heartIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start() } } }
    private fun initLayoutParams() { val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE; layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 } }
    @SuppressLint("ClickableViewAccessibility") private fun setupTouchListener() { binding.root.setOnTouchListener { _, event -> when (event.action) { MotionEvent.ACTION_DOWN -> { initialX = layoutParams.x; initialY = layoutParams.y; initialTouchX = event.rawX; initialTouchY = event.rawY; true } MotionEvent.ACTION_MOVE -> { layoutParams.x = initialX + (event.rawX - initialTouchX).toInt(); layoutParams.y = initialY + (event.rawY - initialTouchY).toInt(); if (isWindowShown) windowManager.updateViewLayout(binding.root, layoutParams); true } else -> false } } }
    private fun updateWindowAppearance() {
        MainScope().launch {
            val textColor = sharedPreferences.getInt("floating_text_color", Color.BLACK)
            val bgColor = sharedPreferences.getInt("floating_bg_color", Color.BLACK)
            val borderColor = sharedPreferences.getInt("floating_border_color", Color.GRAY)
            val bgAlpha = sharedPreferences.getInt("floating_bg_alpha", 10) / 100f
            val borderAlpha = sharedPreferences.getInt("floating_border_alpha", 100) / 100f
            val cornerRadius = sharedPreferences.getInt("floating_corner_radius", 100).toFloat()
            val sizePercent = sharedPreferences.getInt("floating_size", 100)
            val iconSizePercent = sharedPreferences.getInt("floating_icon_size", 100)
            val isBpmTextEnabled = sharedPreferences.getBoolean("bpm_text_enabled", true);
            val isHeartIconEnabled = sharedPreferences.getBoolean("heart_icon_enabled", true);
            val finalBgColor = Color.argb((255 * bgAlpha).roundToInt(), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor));
            val finalBorderColor = Color.argb((255 * borderAlpha).roundToInt(), Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)); val scaleFactor = sizePercent / 100f;
            val iconScaleFactor = iconSizePercent / 100f; val baseIconSizeSp = 18f; val baseTextSizeSp = 16f;
            val baseBpmTextSizeSp = 12f; val basePaddingDp = 8f; val baseMarginDp = 4f;
            binding.floatingBpmText.visibility = if (isBpmTextEnabled) View.VISIBLE else View.GONE;
            binding.floatingHeartIcon.visibility = if (isHeartIconEnabled) View.VISIBLE else View.GONE;
            binding.floatingHeartIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseIconSizeSp * iconScaleFactor);
            binding.floatingBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp * scaleFactor); binding.floatingBpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseBpmTextSizeSp * scaleFactor); binding.floatingHeartIcon.setTextColor(textColor); binding.floatingBpmNumber.setTextColor(textColor); binding.floatingBpmText.setTextColor(textColor); val bpmNumberParams = binding.floatingBpmNumber.layoutParams as LinearLayout.LayoutParams; bpmNumberParams.marginStart = if (isHeartIconEnabled) dpToPx(baseMarginDp * scaleFactor) else 0; binding.floatingBpmNumber.layoutParams = bpmNumberParams; val rootLayoutParams = binding.root.getChildAt(0) as LinearLayout; val paddingPx = dpToPx(basePaddingDp * scaleFactor); rootLayoutParams.setPadding(paddingPx, paddingPx, paddingPx, paddingPx); (binding.root as MaterialCardView).apply { setCardBackgroundColor(finalBgColor); radius = cornerRadius; setStrokeColor(finalBorderColor); strokeWidth = dpToPx(1f) } } }
    private fun dpToPx(dp: Float): Int { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt() }
}