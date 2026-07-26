package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.ble.BleConnectionManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** 自动重连退避序列：5s → 10s → 30s → 60s，之后恒为 60s 封顶 */
class ReconnectBackoffTest {

    @Test
    fun `backoff sequence follows spec`() {
        assertEquals(5_000L, BleConnectionManager.reconnectDelayMs(0))
        assertEquals(10_000L, BleConnectionManager.reconnectDelayMs(1))
        assertEquals(30_000L, BleConnectionManager.reconnectDelayMs(2))
        assertEquals(60_000L, BleConnectionManager.reconnectDelayMs(3))
    }

    @Test
    fun `backoff caps at 60s and never gives up`() {
        assertEquals(60_000L, BleConnectionManager.reconnectDelayMs(4))
        assertEquals(60_000L, BleConnectionManager.reconnectDelayMs(100))
        assertEquals(60_000L, BleConnectionManager.reconnectDelayMs(Int.MAX_VALUE - 1))
    }
}
