package com.example.heart_rate_monitor_mobile.core

import android.content.Context
import com.example.heart_rate_monitor_mobile.ble.BleConnectionManager
import com.example.heart_rate_monitor_mobile.ble.ComparisonDeviceManager
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.data.db.SessionRecorder
import com.example.heart_rate_monitor_mobile.data.location.SpeedMonitor
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import com.example.heart_rate_monitor_mobile.data.update.UpdateRepository
import com.example.heart_rate_monitor_mobile.data.webhook.WebhookRepository
import com.example.heart_rate_monitor_mobile.domain.HeartRateRepository
import com.example.heart_rate_monitor_mobile.service.server.ServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖注入容器（组合根）。
 *
 * 项目规模（~6k 行）不引入 Hilt/Koin：手动构造依赖关系透明、零注解处理开销、
 * 无反射成本。所有进程级单例在此唯一创建；组件（Service/Activity/ViewModel/
 * ContentProvider Initializer）一律经 [AppContainer.get] 取用，不得自行 new。
 *
 * 注意：ContentProvider Initializer 的 onCreate 早于 Application.onCreate，
 * 因此采用惰性 get(context) 而非 Application 字段注入。
 */
class AppContainer private constructor(private val appContext: Context) {

    /** 进程级作用域：SupervisorJob 保证单个子任务失败不拖垮整体 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext, appScope)

    /** 悬浮窗渲染通道协调（前台服务 / 无障碍服务互斥托管） */
    val overlayCoordinator = OverlayCoordinator(settings)
    val database = AppDatabase.getDatabase(appContext)
    val webhooks = WebhookRepository(appContext, appScope)
    val updates = UpdateRepository(appContext, settings)

    private val speedMonitor = SpeedMonitor(appContext, appScope, settings)
    private val sessionRecorder = SessionRecorder(database.heartRateDao(), settings, appScope)
    private val bleConnectionManager = BleConnectionManager(appScope, settings)
    private val comparisonDeviceManager = ComparisonDeviceManager(appScope)

    val heartRate = HeartRateRepository(
        scope = appScope,
        ble = bleConnectionManager,
        speedMonitor = speedMonitor,
        sessionRecorder = sessionRecorder,
        webhooks = webhooks,
        settings = settings,
        comparison = comparisonDeviceManager,
    )

    val serverController = ServerController(settings, appScope, heartRate, appContext)

    init {
        sessionRecorder.cleanupOpenSessions()
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
