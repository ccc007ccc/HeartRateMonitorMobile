package com.example.heart_rate_monitor_mobile.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.example.heart_rate_monitor_mobile.data.settings.KeepAliveChannel
import com.example.heart_rate_monitor_mobile.databinding.LayoutStatusBarOverlayBinding
import com.example.heart_rate_monitor_mobile.service.overlay.HeartbeatAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 状态栏常驻心率服务。
 *
 * 在顶部状态栏区域以 TYPE_APPLICATION_OVERLAY 叠加层绘制紧凑心率条（心形 + BPM）。
 * 重构后数据来自 AppContainer.heartRate（不再 bindService），设置来自 SettingsRepository 流。
 *
 * 自动字色采样（status_bar_auto_color）性能优化：VirtualDisplay 以 1/8 分辨率镜像屏幕，
 * 仅采样状态栏高度区域——相比旧的全分辨率截屏，ImageReader 缓冲内存与采样开销降低约 98%。
 */
class StatusBarResidentService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutStatusBarOverlayBinding
    /** 当前 overlay 所用的宿主 Context（无障碍通道下为无障碍服务，其 WM 携带窗口 token） */
    private var overlayContext: Context = this
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var heartbeatAnimator: HeartbeatAnimator

    private val container by lazy { AppContainer.get(this) }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isOverlayShown = false

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relayout()
        }

        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> hideOverlay()
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕亮起：仅在已解锁时恢复 overlay，避免在锁屏界面之上显示
                    val keyguardManager = getSystemService(KeyguardManager::class.java)
                    if (!keyguardManager.isKeyguardLocked) {
                        showOverlay()
                    }
                }
                Intent.ACTION_USER_PRESENT -> showOverlay()
            }
        }
    }

    // ========== MediaProjection 截屏采样相关 ==========
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSampling = false
    // 采样结果：true=背景偏深→用白字，false=背景偏浅→用黑字
    private var sampledUseWhiteText = false
    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleStatusBarBrightness()
            if (isSampling) {
                sampleHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
    }

    // ========== 前台服务保活 + 自愈检查 ==========
    // specialUse 前台服务防止系统在锁屏/内存压力下杀死服务，保证 overlay 持续可用。
    private var isResidentForeground = false
    // 标记服务正在销毁，避免 stopMediaProjectionSampling 中误调 ensureResidentForeground
    private var isDestroying = false
    private val safetyHandler = Handler(Looper.getMainLooper())

    /**
     * 周期性自愈检查：屏幕亮且已解锁时，若 overlay 未显示或被系统移除则重新添加。
     * 兜底处理广播遗漏、服务被杀后 START_STICKY 重启等场景，确保锁屏解锁后 overlay 自动恢复。
     */
    private val overlaySafetyCheck = object : Runnable {
        override fun run() {
            try {
                if (container.settings.settings.value.statusBar.residentEnabled) {
                    val powerManager = getSystemService(PowerManager::class.java)
                    val keyguardManager = getSystemService(KeyguardManager::class.java)
                    val systemUiExpanded = container.overlayCoordinator.systemUiExpanded.value
                    if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked && !systemUiExpanded) {
                        if (!isOverlayShown || !binding.root.isAttachedToWindow) {
                            showOverlay()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "自愈检查失败", e)
            }
            safetyHandler.postDelayed(this, SAFETY_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        rebuildOverlayView()

        initLayoutParams()
        applyAppearance()

        observeData()
        observeSettings()

        applicationContext.registerComponentCallbacks(componentCallbacks)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // 启动周期性自愈检查，兜底恢复 overlay
        safetyHandler.postDelayed(overlaySafetyCheck, SAFETY_CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShowOverlay()) {
            // 防御性：无悬浮窗权限则不显示
            stopSelf()
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_START_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startMediaProjectionSampling(resultCode, resultData)
                }
            }
            ACTION_STOP_MEDIA_PROJECTION -> stopMediaProjectionSampling()
            else -> {
                // 普通启动 / START_STICKY 重启：先提升为前台服务保活，再显示 overlay
                ensureResidentForeground()
                showOverlay()
            }
        }
        return START_STICKY
    }

    private fun observeData() {
        serviceScope.launch {
            container.heartRate.heartRate.collectLatest { rate ->
                binding.statusBarBpmNumber.text = if (rate > 0) "$rate" else "--"
                heartbeatAnimator.update(
                    rate,
                    container.settings.settings.value.general.heartbeatAnimationEnabled &&
                        container.heartRate.isDeviceConnected(),
                )
            }
        }
    }

    /** 设置流驱动外观/尺寸/位置更新（替代 OnSharedPreferenceChangeListener） */
    private fun observeSettings() {
        serviceScope.launch {
            container.settings.flowOf { it.statusBar }.collectLatest {
                if (!isOverlayShown) return@collectLatest
                relayout()
            }
        }
        // 保活通道切换：窗口类型与宿主 WindowManager 均变化，必须重建视图与窗口
        serviceScope.launch {
            combine(
                container.overlayCoordinator.accessibilityActive,
                container.settings.flowOf { it.general.keepAliveChannel },
            ) { active, channel -> active to channel }
                .collectLatest {
                    val wasShown = isOverlayShown
                    hideOverlay()
                    rebuildOverlayView()
                    initLayoutParams()
                    applyAppearance()
                    if (wasShown) showOverlay()
                    // 切到无障碍通道立即撤掉常驻通知；切回前台通道补回保活
                    ensureResidentForeground()
                }
        }
        // 无障碍 overlay 层级高于下拉通知面板，展开时临时让位，收起后恢复
        serviceScope.launch {
            container.overlayCoordinator.systemUiExpanded.collectLatest { expanded ->
                if (!container.overlayCoordinator.accessibilityActive.value) return@collectLatest
                if (expanded) {
                    hideOverlay()
                } else if (container.settings.settings.value.statusBar.residentEnabled) {
                    showOverlay()
                }
            }
        }
    }

    /**
     * 以 specialUse 类型提升为前台服务，防止系统在锁屏/内存压力下杀死服务。
     * - 首次由 SettingsActivity（前台）启动时：startForeground 成功，持续保活。
     * - START_STICKY 重启时若 app 在后台：startForeground 可能抛
     *   ForegroundServiceStartNotAllowedException，捕获后降级为普通服务，
     *   overlay 仍可显示；用户下次打开 App 时会重新建立前台状态。
     * - 采样期间（isSampling）不抢占类型，由 startMediaProjectionSampling 管理。
     */
    private fun ensureResidentForeground() {
        // 无障碍通道：进程由系统绑定的无障碍服务保活，无需前台通知（本功能的核心卖点）
        if (container.overlayCoordinator.isAccessibilityChannel()) {
            if (isResidentForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isResidentForeground = false
            }
            return
        }
        if (isSampling || isResidentForeground) return
        try {
            val notification = createResidentNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isResidentForeground = true
        } catch (e: Exception) {
            // 后台 START_STICKY 重启时可能被拒绝，降级为普通服务
            Log.w(TAG, "无法提升为前台服务，降级运行", e)
            isResidentForeground = false
        }
    }

    /**
     * 常驻前台通知（低重要性：不在状态栏显示，仅在通知栏可见）。
     */
    private fun createResidentNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            RESIDENT_CHANNEL_ID,
            getString(R.string.notif_channel_statusbar_resident),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_statusbar_resident_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        return Notification.Builder(this, RESIDENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.statusbar_notif_title))
            .setContentText(getString(R.string.statusbar_notif_running))
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .build()
    }

    /**
     * 窗口类型按保活通道选择（v2.2）：
     * - 无障碍通道：TYPE_ACCESSIBILITY_OVERLAY——免悬浮窗权限，且层级高于系统状态栏
     *   （不再被状态栏内容遮挡）；坐标系与旧类型一致，x/y 微调设置照常生效。
     * - 前台通道：TYPE_APPLICATION_OVERLAY（原行为）。
     */
    /**
     * 按当前通道重建 overlay 视图与 WindowManager。
     *
     * 关键点（v2.2 修复）：TYPE_ACCESSIBILITY_OVERLAY 必须由**无障碍服务的 WindowManager**
     * 添加——它携带无障碍窗口 token；用普通 Service 的 WM 会被 WMS 以 BadToken 拒绝，
     * 表现为 overlay 静默不显示。因此无障碍通道下改用协调器登记的无障碍 Context。
     */
    private fun rebuildOverlayView() {
        val accessibilityContext = container.overlayCoordinator.accessibilityContext
        overlayContext = if (
            accessibilityContext != null &&
            container.settings.settings.value.general.keepAliveChannel == KeepAliveChannel.ACCESSIBILITY
        ) accessibilityContext else this
        windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themedContext = ContextThemeWrapper(overlayContext, R.style.Theme_HeartRateMonitorMobile)
        binding = LayoutStatusBarOverlayBinding.inflate(LayoutInflater.from(themedContext))
        heartbeatAnimator = HeartbeatAnimator(binding.statusBarHeartIcon)
    }

    private fun overlayWindowType(): Int = when {
        isAccessibilityOverlay() ->
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun initLayoutParams() {
        val type = overlayWindowType()

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            getStatusBarHeight(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // 位置由用户设置控制（水平位置百分比 + 垂直微调）
        updatePosition()
    }

    private fun showOverlay() {
        // 已标记显示且窗口确实 attached：无需重复添加
        if (isOverlayShown && binding.root.isAttachedToWindow) return
        // 状态不一致修正：标记显示但窗口已被系统移除（锁屏/内存压力），重置标记
        isOverlayShown = false
        if (!canShowOverlay()) return
        try {
            applySize()
            applyTextStyle()
            // 防御性：如果窗口仍 attached（理论不该发生），先移除避免重复添加异常
            if (binding.root.isAttachedToWindow) {
                windowManager.removeView(binding.root)
            }
            windowManager.addView(binding.root, layoutParams)
            isOverlayShown = true
            applyAppearance()
        } catch (e: Exception) {
            Log.w(TAG, "添加 overlay 失败", e)
        }
    }

    /** 无障碍通道且已拿到 token 化 Context 时走 accessibility overlay */
    private fun isAccessibilityOverlay(): Boolean =
        overlayContext !== this && container.overlayCoordinator.isAccessibilityChannel()

    /**
     * 是否具备显示 overlay 的条件。
     * 无障碍通道免悬浮窗权限——判据用通道设置而非 overlayContext，
     * 避免"无障碍已选但 Context 尚未登记"的时序下服务自杀。
     */
    private fun canShowOverlay(): Boolean =
        container.overlayCoordinator.isAccessibilityChannel() ||
            container.settings.settings.value.general.keepAliveChannel == KeepAliveChannel.ACCESSIBILITY ||
            Settings.canDrawOverlays(this)

    private fun hideOverlay() {
        if (!isOverlayShown) return
        try {
            windowManager.removeView(binding.root)
        } catch (e: Exception) {
            Log.w(TAG, "移除 overlay 失败", e)
        }
        isOverlayShown = false
    }

    /**
     * 刷新文字/图标颜色（纯色，无阴影/描边）。
     *
     * 颜色判定优先级：
     * 1. status_bar_auto_color = true → 用 MediaProjection 采样结果（sampledUseWhiteText）
     * 2. status_bar_white_text = true → 纯白
     * 3. 否则 → 纯黑（默认）
     */
    private fun applyAppearance() {
        val statusBar = container.settings.settings.value.statusBar
        val useWhite = if (statusBar.autoColor) sampledUseWhiteText else statusBar.whiteText
        val textColor = if (useWhite) Color.WHITE else Color.BLACK

        binding.statusBarHeartIcon.setColorFilter(textColor)
        binding.statusBarBpmNumber.setTextColor(textColor)
        binding.statusBarBpmUnit.setTextColor(textColor)
    }

    /** 根据用户设置的整体大小缩放图标、文字、内边距与间距 */
    private fun applySize() {
        val scaleFactor = container.settings.settings.value.statusBar.sizePercent / 100f

        val iconSize = dpToPx(14f * scaleFactor)
        binding.statusBarHeartIcon.layoutParams = binding.statusBarHeartIcon.layoutParams.apply {
            width = iconSize
            height = iconSize
        }

        binding.statusBarBpmNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scaleFactor)
        binding.statusBarBpmUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f * scaleFactor)

        val padding = dpToPx(6f * scaleFactor)
        binding.root.setPadding(padding, 0, padding, 0)

        val numberMargin = dpToPx(3f * scaleFactor)
        (binding.statusBarBpmNumber.layoutParams as LinearLayout.LayoutParams).marginStart = numberMargin
        val unitMargin = dpToPx(1f * scaleFactor)
        (binding.statusBarBpmUnit.layoutParams as LinearLayout.LayoutParams).marginStart = unitMargin
    }

    /**
     * 根据用户设置控制 "bpm" 单位文字显隐与心率数字粗细。
     * - bpmTextEnabled：true 显示 "bpm" 单位（图标+80+bpm），false 隐藏，心率数字始终显示
     * - textThickness：0-100，在原有 bold 基础上叠加 stroke 宽度实现可调加粗
     */
    private fun applyTextStyle() {
        val statusBar = container.settings.settings.value.statusBar

        binding.statusBarBpmUnit.visibility = if (statusBar.bpmTextEnabled) View.VISIBLE else View.GONE

        val numberPaint = binding.statusBarBpmNumber.paint
        val unitPaint = binding.statusBarBpmUnit.paint
        if (statusBar.textThickness > 0) {
            // stroke 宽度按文字大小比例缩放，避免小文字过粗
            val numberStroke = binding.statusBarBpmNumber.textSize * statusBar.textThickness / 100f * 0.25f
            val unitStroke = binding.statusBarBpmUnit.textSize * statusBar.textThickness / 100f * 0.25f
            numberPaint.style = Paint.Style.FILL_AND_STROKE
            numberPaint.strokeWidth = numberStroke
            unitPaint.style = Paint.Style.FILL_AND_STROKE
            unitPaint.strokeWidth = unitStroke
        } else {
            numberPaint.style = Paint.Style.FILL
            numberPaint.strokeWidth = 0f
            unitPaint.style = Paint.Style.FILL
            unitPaint.strokeWidth = 0f
        }
        binding.statusBarBpmNumber.invalidate()
        binding.statusBarBpmUnit.invalidate()
    }

    /**
     * 根据用户设置刷新水平位置和垂直微调。
     * 水平位置：0-100 映射为屏幕宽度的百分比；垂直微调：0-20 映射为 -10 到 +10 dp。
     */
    private fun updatePosition() {
        val statusBar = container.settings.settings.value.statusBar
        val screenWidth = resources.displayMetrics.widthPixels
        layoutParams.x = (screenWidth * statusBar.xPositionPercent / 100f).toInt()
        layoutParams.y = dpToPx((statusBar.yOffset - 10).toFloat())
    }

    private fun relayout() {
        applyAppearance()
        applySize()
        applyTextStyle()
        updatePosition()
        layoutParams.height = getStatusBarHeight()
        if (isOverlayShown) {
            try {
                windowManager.updateViewLayout(binding.root, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "更新 overlay 布局失败", e)
            }
        }
    }

    private fun getStatusBarHeight(): Int {
        val res = resources
        // status_bar_height 无公开 API 替代，只能经 getIdentifier 读取
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val resourceId = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            res.getDimensionPixelSize(resourceId)
        } else {
            dpToPx(24f)
        }
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        isDestroying = true
        safetyHandler.removeCallbacks(overlaySafetyCheck)
        stopMediaProjectionSampling()
        hideOverlay()
        heartbeatAnimator.stop()
        serviceScope.cancel()
        try {
            applicationContext.unregisterComponentCallbacks(componentCallbacks)
        } catch (e: Exception) {
            Log.w(TAG, "注销 componentCallbacks 失败", e)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "注销 screenReceiver 失败", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        isResidentForeground = false
    }

    companion object {
        const val ACTION_START_MEDIA_PROJECTION =
            "com.example.heart_rate_monitor_mobile.START_MEDIA_PROJECTION"
        const val ACTION_STOP_MEDIA_PROJECTION =
            "com.example.heart_rate_monitor_mobile.STOP_MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "StatusBarResident"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val NOTIFICATION_ID = 0x5B01
        private const val CHANNEL_ID = "status_bar_sampling"
        private const val RESIDENT_CHANNEL_ID = "status_bar_resident"
        private const val SAFETY_CHECK_INTERVAL_MS = 3000L
        private const val LUMINANCE_THRESHOLD = 128.0
        /** VirtualDisplay 降采样倍率：镜像分辨率 = 屏幕分辨率 / 8，内存与 CPU 占用约为全屏的 1/64 */
        private const val PROJECTION_DOWNSCALE = 8
    }

    /**
     * 启动 MediaProjection 截屏采样（降采样版）。
     *
     * Android 14+（API 34）要求 MediaProjection 必须在 mediaProjection 类型的前台服务中运行，
     * 故先调 startForeground；API 29+ 用 3 参版本指定类型，低版本退回 2 参。
     *
     * 注意：这是无障碍通道下唯一会出现通知的场景——MediaProjection 是平台硬性要求
     * （必须运行在 mediaProjection 类型前台服务中），无法规避；仅在"自动识别颜色"
     * 开启期间存在，关闭后立即撤销（见 stopMediaProjectionSampling）。
     *
     * 隐私/性能：VirtualDisplay 只以 1/8 分辨率镜像，采样逻辑只读取状态栏高度对应的顶部区域。
     */
    private fun startMediaProjectionSampling(resultCode: Int, data: Intent) {
        try {
            val notification = createSamplingNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            // 采样期间由 mediaProjection 类型接管前台，常驻标记重置
            isResidentForeground = false

            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isSampling = false
                    sampleHandler.removeCallbacks(sampleRunnable)
                }
            }, sampleHandler)

            val metrics = resources.displayMetrics
            val scaledWidth = (metrics.widthPixels / PROJECTION_DOWNSCALE).coerceAtLeast(1)
            val scaledHeight = (metrics.heightPixels / PROJECTION_DOWNSCALE).coerceAtLeast(1)
            imageReader = ImageReader.newInstance(
                scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2,
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "StatusBarSample",
                scaledWidth, scaledHeight,
                (metrics.densityDpi / PROJECTION_DOWNSCALE).coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, sampleHandler,
            )

            isSampling = true
            sampleHandler.post(sampleRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕采样失败", e)
            isSampling = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** 停止 MediaProjection 截屏采样，释放资源并回退前台服务类型 */
    private fun stopMediaProjectionSampling() {
        isSampling = false
        sampleHandler.removeCallbacks(sampleRunnable)
        try { virtualDisplay?.release() } catch (e: Exception) { Log.w(TAG, "释放 virtualDisplay 失败", e) }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { Log.w(TAG, "关闭 imageReader 失败", e) }
        imageReader = null
        try { mediaProjection?.stop() } catch (e: Exception) { Log.w(TAG, "停止 mediaProjection 失败", e) }
        mediaProjection = null
        sampledUseWhiteText = false
        // 停止采样后回退前台状态：
        // - 无障碍通道：不需要任何常驻通知，直接撤掉采样期间的前台通知
        //   （ensureResidentForeground 在该通道下会早退，不能依赖它收尾）
        // - 前台通道且常驻仍开启：回退到 specialUse 常驻前台类型继续保活
        val accessibilityChannel = container.overlayCoordinator.isAccessibilityChannel()
        if (!isDestroying && !accessibilityChannel &&
            container.settings.settings.value.statusBar.residentEnabled
        ) {
            isResidentForeground = false  // 重置以允许 ensureResidentForeground 重新提升
            ensureResidentForeground()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isResidentForeground = false
        }
        applyAppearance()
    }

    /**
     * 采样状态栏区域亮度，更新 sampledUseWhiteText 并刷新外观。
     * 亮度 > 阈值 → 浅色背景 → 黑字；否则 → 白字。
     * 镜像已是 1/8 分辨率，仅遍历顶部 状态栏高度/8 的行。
     */
    private fun sampleStatusBarBrightness() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val scaledWidth = image.width
            val scaledStatusBarHeight =
                (getStatusBarHeight() / PROJECTION_DOWNSCALE).coerceAtLeast(1)

            var totalLuminance = 0.0
            var sampleCount = 0
            var y = 0
            while (y < scaledStatusBarHeight) {
                var x = 0
                while (x < scaledWidth) {
                    val pixelIndex = y * rowStride + x * pixelStride
                    if (pixelIndex + 2 < buffer.capacity()) {
                        val r = buffer.get(pixelIndex).toInt() and 0xFF
                        val g = buffer.get(pixelIndex + 1).toInt() and 0xFF
                        val b = buffer.get(pixelIndex + 2).toInt() and 0xFF
                        totalLuminance += 0.299 * r + 0.587 * g + 0.114 * b
                        sampleCount++
                    }
                    x += 2
                }
                y++
            }

            if (sampleCount > 0) {
                val avgLuminance = totalLuminance / sampleCount
                val newUseWhite = avgLuminance < LUMINANCE_THRESHOLD
                if (newUseWhite != sampledUseWhiteText) {
                    sampledUseWhiteText = newUseWhite
                    applyAppearance()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "采样状态栏亮度失败", e)
        } finally {
            image.close()
        }
    }

    private fun createSamplingNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_color_sampling),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_color_sampling_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.statusbar_notif_title))
            .setContentText(getString(R.string.statusbar_notif_detecting))
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
