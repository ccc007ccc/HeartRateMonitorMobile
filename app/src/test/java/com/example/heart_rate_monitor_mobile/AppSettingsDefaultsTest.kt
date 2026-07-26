package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置默认值契约测试：AppSettings 是全项目默认值的唯一定义处，
 * 此处锁定关键项，防止历史上"speed_display_enabled 在不同文件默认值不一致"
 * 一类 bug 复发。
 */
class AppSettingsDefaultsTest {

    private val defaults = AppSettings()

    @Test
    fun `speed display defaults to false`() {
        // 历史 bug：BleService 默认 true 而设置页默认 false，导致新装用户无感知 GPS 耗电
        assertFalse(defaults.general.speedDisplayEnabled)
    }

    @Test
    fun `connection defaults`() {
        assertFalse(defaults.connection.autoConnectEnabled)
        assertTrue(defaults.connection.autoReconnectEnabled)
        assertNull(defaults.connection.favoriteDeviceId)
        assertNull(defaults.connection.lastConnectedDeviceId)
        assertEquals("[]", defaults.connection.favoriteDeviceHistoryJson)
    }

    @Test
    fun `alarm defaults`() {
        assertFalse(defaults.alarm.enabled)
        assertEquals(100, defaults.alarm.highThreshold)
        assertEquals(50, defaults.alarm.lowThreshold)
        assertEquals(10, defaults.alarm.durationSeconds)
        assertFalse(defaults.alarm.repeatEnabled)
        assertEquals(5, defaults.alarm.repeatIntervalMinutes)
    }

    @Test
    fun `server defaults are safe`() {
        assertFalse(defaults.server.httpEnabled)
        assertFalse(defaults.server.webSocketEnabled)
        // 生态兼容默认：认证关闭（HeartRateWidget/桌面版免配置直连）
        assertFalse(defaults.server.authRequired)
        assertEquals(8000, defaults.server.httpPort)
        assertEquals(8001, defaults.server.webSocketPort)
    }

    @Test
    fun `general and history defaults`() {
        assertTrue(defaults.general.monetColorEnabled)
        assertFalse(defaults.general.hideFromRecentsEnabled)
        assertFalse(defaults.general.historyRecordingEnabled)
        assertTrue(defaults.general.heartbeatAnimationEnabled)
    }
}
