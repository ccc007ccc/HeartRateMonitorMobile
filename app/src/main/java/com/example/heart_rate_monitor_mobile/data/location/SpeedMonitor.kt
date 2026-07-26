package com.example.heart_rate_monitor_mobile.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GPS 速度采集。跟随 speed_display_enabled 设置自动启停，
 * 输出 km/h 的 StateFlow。设置关闭或权限缺失时保持 0。
 */
class SpeedMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _speed.value = if (location.hasSpeed()) location.speed * 3.6f else 0f
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        scope.launch {
            settings.flowOf { it.general.speedDisplayEnabled }.collect { refresh() }
        }
    }

    /** 重新评估启停条件（设置变化或权限刚被授予时调用） */
    suspend fun refresh() = withContext(Dispatchers.Main) {
        val enabled = settings.settings.value.general.speedDisplayEnabled
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (enabled && hasPermission && !isListening) {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
                try {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                    isListening = true
                } catch (e: SecurityException) {
                    Log.e(TAG, "请求位置更新失败", e)
                }
            } else {
                Log.w(TAG, "设备不支持 GPS，无法获取速度信息")
            }
        } else if ((!enabled || !hasPermission) && isListening) {
            locationManager?.removeUpdates(locationListener)
            isListening = false
            _speed.value = 0f
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    private companion object {
        const val TAG = "SpeedMonitor"
        const val UPDATE_INTERVAL_MS = 1000L
        const val MIN_DISTANCE_M = 1f
    }
}
