package com.example.heart_rate_monitor_mobile.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 全部设置键。键名与迁移前 SharedPreferences("app_settings") 完全一致，
 * SharedPreferencesMigration 会在首次读取时把老用户数据原样迁入 DataStore。
 */
object SettingsKeys {
    // 连接
    val AUTO_CONNECT_ENABLED = booleanPreferencesKey("auto_connect_enabled")
    val AUTO_RECONNECT_ENABLED = booleanPreferencesKey("auto_reconnect_enabled")
    val FAVORITE_DEVICE_ID = stringPreferencesKey("favorite_device_id")
    val LAST_CONNECTED_DEVICE_ID = stringPreferencesKey("last_connected_device_id")
    val FAVORITE_DEVICE_HISTORY = stringPreferencesKey("favorite_device_history")

    // 通用
    val MONET_COLOR_ENABLED = booleanPreferencesKey("monet_color_enabled")
    val HIDE_FROM_RECENTS_ENABLED = booleanPreferencesKey("hide_from_recents_enabled")
    val SPEED_DISPLAY_ENABLED = booleanPreferencesKey("speed_display_enabled")
    val HISTORY_RECORDING_ENABLED = booleanPreferencesKey("history_recording_enabled")
    val HEARTBEAT_ANIMATION_ENABLED = booleanPreferencesKey("heartbeat_animation_enabled")

    // 悬浮窗
    val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
    val FLOATING_BPM_TEXT_ENABLED = booleanPreferencesKey("bpm_text_enabled")
    val FLOATING_HEART_ICON_ENABLED = booleanPreferencesKey("heart_icon_enabled")
    val FLOATING_SIZE = intPreferencesKey("floating_size")
    val FLOATING_ICON_SIZE = intPreferencesKey("floating_icon_size")
    val FLOATING_TEXT_COLOR = intPreferencesKey("floating_text_color")
    val FLOATING_BG_COLOR = intPreferencesKey("floating_bg_color")
    val FLOATING_BG_ALPHA = intPreferencesKey("floating_bg_alpha")
    val FLOATING_BORDER_COLOR = intPreferencesKey("floating_border_color")
    val FLOATING_BORDER_ALPHA = intPreferencesKey("floating_border_alpha")
    val FLOATING_CORNER_RADIUS = intPreferencesKey("floating_corner_radius")

    // 状态栏常驻
    val STATUS_BAR_RESIDENT_ENABLED = booleanPreferencesKey("status_bar_resident_enabled")
    val STATUS_BAR_BPM_TEXT_ENABLED = booleanPreferencesKey("status_bar_bpm_text_enabled")
    val STATUS_BAR_AUTO_COLOR = booleanPreferencesKey("status_bar_auto_color")
    val STATUS_BAR_WHITE_TEXT = booleanPreferencesKey("status_bar_white_text")
    val STATUS_BAR_SIZE = intPreferencesKey("status_bar_size")
    val STATUS_BAR_TEXT_THICKNESS = intPreferencesKey("status_bar_text_thickness")
    val STATUS_BAR_X_POSITION = intPreferencesKey("status_bar_x_position")
    val STATUS_BAR_Y_OFFSET = intPreferencesKey("status_bar_y_offset")

    // 心率预警
    val ALARM_ENABLED = booleanPreferencesKey("heart_rate_alarm_enabled")
    val ALARM_HIGH_THRESHOLD = intPreferencesKey("heart_rate_alarm_high_threshold")
    val ALARM_LOW_THRESHOLD = intPreferencesKey("heart_rate_alarm_low_threshold")
    val ALARM_DURATION_SECONDS = intPreferencesKey("heart_rate_alarm_duration_seconds")
    val ALARM_REPEAT_ENABLED = booleanPreferencesKey("heart_rate_alarm_repeat_enabled")
    val ALARM_REPEAT_INTERVAL_MINUTES = intPreferencesKey("heart_rate_alarm_repeat_interval_minutes")
    val POSTURE_CALIBRATION_DATA = stringPreferencesKey("posture_calibration_data")

    // 内置服务器
    val HTTP_SERVER_ENABLED = booleanPreferencesKey("http_server_enabled")
    val HTTP_SERVER_PORT = intPreferencesKey("http_server_port")
    val WEBSOCKET_SERVER_ENABLED = booleanPreferencesKey("websocket_server_enabled")
    val WEBSOCKET_SERVER_PORT = intPreferencesKey("websocket_server_port")
    val SERVER_ALLOW_LAN = booleanPreferencesKey("server_allow_lan")
    val SERVER_AUTH_TOKEN = stringPreferencesKey("server_auth_token")
}

/** DataStore 文件名沿用 "app_settings"，迁移源为同名 SharedPreferences */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "app_settings"))
    }
)

/**
 * 设置仓库：全项目唯一的设置读写入口。
 *
 * - 读：[settings] StateFlow 提供进程内实时快照，`settings.value` 可同步取值
 *   （替代旧的 getSharedPreferences 同步读取）；[flowOf] 提供单项变更流
 *   （替代旧的 OnSharedPreferenceChangeListener）。
 * - 写：[set]（挂起）或 [setAsync]（即发即忘，等价旧 SharedPreferences.apply()）。
 * - 老用户数据经 SharedPreferencesMigration 无损迁移。
 */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {

    private val dataStore = context.applicationContext.settingsDataStore

    /**
     * 进程启动时同步读取一次（触发迁移），此后由 DataStore 变更驱动。
     * 与旧 SharedPreferences 首次 getSharedPreferences 的同步磁盘读取成本相当。
     */
    val settings: StateFlow<AppSettings> = dataStore.data
        .map { it.toAppSettings() }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            runBlocking { dataStore.data.first().toAppSettings() },
        )

    /** 单个设置项的变更流（去重），用于替代 OnSharedPreferenceChangeListener */
    fun <T> flowOf(selector: (AppSettings) -> T): Flow<T> =
        settings.map(selector).distinctUntilChanged()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    suspend fun <T> remove(key: Preferences.Key<T>) {
        dataStore.edit { it.remove(key) }
    }

    /** 即发即忘写入，语义等价旧 SharedPreferences.edit().apply() */
    fun <T> setAsync(key: Preferences.Key<T>, value: T) {
        scope.launch { set(key, value) }
    }

    fun <T> removeAsync(key: Preferences.Key<T>) {
        scope.launch { remove(key) }
    }

    private companion object {
        fun Preferences.toAppSettings() = AppSettings(
            connection = ConnectionSettings(
                autoConnectEnabled = this[SettingsKeys.AUTO_CONNECT_ENABLED] ?: false,
                autoReconnectEnabled = this[SettingsKeys.AUTO_RECONNECT_ENABLED] ?: true,
                favoriteDeviceId = this[SettingsKeys.FAVORITE_DEVICE_ID],
                lastConnectedDeviceId = this[SettingsKeys.LAST_CONNECTED_DEVICE_ID],
                favoriteDeviceHistoryJson = this[SettingsKeys.FAVORITE_DEVICE_HISTORY] ?: "[]",
            ),
            general = GeneralSettings(
                monetColorEnabled = this[SettingsKeys.MONET_COLOR_ENABLED] ?: true,
                hideFromRecentsEnabled = this[SettingsKeys.HIDE_FROM_RECENTS_ENABLED] ?: false,
                speedDisplayEnabled = this[SettingsKeys.SPEED_DISPLAY_ENABLED] ?: false,
                historyRecordingEnabled = this[SettingsKeys.HISTORY_RECORDING_ENABLED] ?: false,
                heartbeatAnimationEnabled = this[SettingsKeys.HEARTBEAT_ANIMATION_ENABLED] ?: true,
            ),
            floating = FloatingWindowSettings(
                enabled = this[SettingsKeys.FLOATING_WINDOW_ENABLED] ?: false,
                bpmTextEnabled = this[SettingsKeys.FLOATING_BPM_TEXT_ENABLED] ?: true,
                heartIconEnabled = this[SettingsKeys.FLOATING_HEART_ICON_ENABLED] ?: true,
                sizePercent = this[SettingsKeys.FLOATING_SIZE] ?: 100,
                iconSizePercent = this[SettingsKeys.FLOATING_ICON_SIZE] ?: 100,
                textColor = this[SettingsKeys.FLOATING_TEXT_COLOR] ?: FloatingWindowSettings().textColor,
                backgroundColor = this[SettingsKeys.FLOATING_BG_COLOR] ?: FloatingWindowSettings().backgroundColor,
                backgroundAlphaPercent = this[SettingsKeys.FLOATING_BG_ALPHA] ?: 10,
                borderColor = this[SettingsKeys.FLOATING_BORDER_COLOR] ?: FloatingWindowSettings().borderColor,
                borderAlphaPercent = this[SettingsKeys.FLOATING_BORDER_ALPHA] ?: 100,
                cornerRadius = this[SettingsKeys.FLOATING_CORNER_RADIUS] ?: 100,
            ),
            statusBar = StatusBarSettings(
                residentEnabled = this[SettingsKeys.STATUS_BAR_RESIDENT_ENABLED] ?: false,
                bpmTextEnabled = this[SettingsKeys.STATUS_BAR_BPM_TEXT_ENABLED] ?: true,
                autoColor = this[SettingsKeys.STATUS_BAR_AUTO_COLOR] ?: false,
                whiteText = this[SettingsKeys.STATUS_BAR_WHITE_TEXT] ?: false,
                sizePercent = this[SettingsKeys.STATUS_BAR_SIZE] ?: 100,
                textThickness = this[SettingsKeys.STATUS_BAR_TEXT_THICKNESS] ?: 0,
                xPositionPercent = this[SettingsKeys.STATUS_BAR_X_POSITION] ?: 0,
                yOffset = this[SettingsKeys.STATUS_BAR_Y_OFFSET] ?: 10,
            ),
            alarm = AlarmSettings(
                enabled = this[SettingsKeys.ALARM_ENABLED] ?: false,
                highThreshold = this[SettingsKeys.ALARM_HIGH_THRESHOLD] ?: 100,
                lowThreshold = this[SettingsKeys.ALARM_LOW_THRESHOLD] ?: 50,
                durationSeconds = this[SettingsKeys.ALARM_DURATION_SECONDS] ?: 10,
                repeatEnabled = this[SettingsKeys.ALARM_REPEAT_ENABLED] ?: false,
                repeatIntervalMinutes = this[SettingsKeys.ALARM_REPEAT_INTERVAL_MINUTES] ?: 5,
                postureCalibrationJson = this[SettingsKeys.POSTURE_CALIBRATION_DATA],
            ),
            server = ServerSettings(
                httpEnabled = this[SettingsKeys.HTTP_SERVER_ENABLED] ?: false,
                httpPort = this[SettingsKeys.HTTP_SERVER_PORT] ?: 8000,
                webSocketEnabled = this[SettingsKeys.WEBSOCKET_SERVER_ENABLED] ?: false,
                webSocketPort = this[SettingsKeys.WEBSOCKET_SERVER_PORT] ?: 8001,
                allowLan = this[SettingsKeys.SERVER_ALLOW_LAN] ?: false,
                authToken = this[SettingsKeys.SERVER_AUTH_TOKEN] ?: "",
            ),
        )
    }
}
