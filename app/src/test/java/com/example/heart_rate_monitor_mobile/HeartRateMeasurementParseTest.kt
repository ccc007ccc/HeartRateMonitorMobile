package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.ble.BleConnectionManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Heart Rate Measurement (0x2A37) 帧解析：
 * flags bit0=uint16 BPM、bit3=Energy Expended（跳过 2 字节）、bit4=RR 间期（1/1024 秒 → 毫秒）。
 */
class HeartRateMeasurementParseTest {

    private fun parse(vararg bytes: Int) =
        BleConnectionManager.parseHeartRateMeasurement(
            ByteArray(bytes.size) { bytes[it].toByte() }
        )

    @Test
    fun `uint8 bpm without rr`() {
        val m = parse(0x00, 72)
        assertEquals(72, m.bpm)
        assertEquals(emptyList<Int>(), m.rrIntervalsMs)
    }

    @Test
    fun `uint16 bpm little endian`() {
        val m = parse(0x01, 0x2C, 0x01) // 0x012C = 300
        assertEquals(300, m.bpm)
    }

    @Test
    fun `rr intervals converted from 1024ths to millis`() {
        // flags 0x10：RR 存在；raw 1024 → 1000ms，raw 512 → 500ms
        val m = parse(0x10, 72, 0x00, 0x04, 0x00, 0x02)
        assertEquals(72, m.bpm)
        assertEquals(listOf(1000, 500), m.rrIntervalsMs)
    }

    @Test
    fun `energy expended bytes are skipped before rr`() {
        // flags 0x18：Energy Expended(2 字节 0x1234) + RR(raw 1024)
        val m = parse(0x18, 80, 0x34, 0x12, 0x00, 0x04)
        assertEquals(80, m.bpm)
        assertEquals(listOf(1000), m.rrIntervalsMs)
    }

    @Test
    fun `malformed frames yield zero bpm and no rr`() {
        assertEquals(0, parse().bpm)             // 空帧
        assertEquals(0, parse(0x00).bpm)         // 只有 flags
        assertEquals(0, parse(0x01, 0x50).bpm)   // uint16 缺高字节
        // RR 标志位存在但字节数为奇数：残缺尾字节被忽略
        assertEquals(listOf(1000), parse(0x10, 72, 0x00, 0x04, 0x00).rrIntervalsMs)
    }
}
