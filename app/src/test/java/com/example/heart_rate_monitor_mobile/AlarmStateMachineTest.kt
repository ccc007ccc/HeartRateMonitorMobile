package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.domain.AlarmStateMachine
import com.example.heart_rate_monitor_mobile.service.posture.PostureType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 心率预警状态机单元测试（时间全部由测试注入，不依赖真实时钟）。
 */
class AlarmStateMachineTest {

    private data class Alarm(val bpm: Int, val isHigh: Boolean, val threshold: Int)

    private class Harness(
        high: Int = 100,
        low: Int = 50,
        durationMs: Long = 10_000L,
        cooldownMs: Long = 60_000L,
    ) {
        val alarms = mutableListOf<Alarm>()
        val machine = AlarmStateMachine(high, low, durationMs, cooldownMs) { bpm, isHigh, _, threshold ->
            alarms.add(Alarm(bpm, isHigh, threshold))
        }
    }

    @Test
    fun `high breach must persist for duration before alarming`() {
        val h = Harness()
        h.machine.onHeartRate(120, PostureType.SITTING, now = 0L)
        h.machine.onHeartRate(120, PostureType.SITTING, now = 9_999L)
        assertEquals(0, h.alarms.size)
        h.machine.onHeartRate(121, PostureType.SITTING, now = 10_000L)
        assertEquals(1, h.alarms.size)
        assertEquals(Alarm(121, isHigh = true, threshold = 100), h.alarms[0])
    }

    @Test
    fun `low breach alarms after duration`() {
        val h = Harness()
        h.machine.onHeartRate(40, PostureType.STANDING, now = 0L)
        h.machine.onHeartRate(41, PostureType.STANDING, now = 10_000L)
        assertEquals(1, h.alarms.size)
        assertEquals(Alarm(41, isHigh = false, threshold = 50), h.alarms[0])
    }

    @Test
    fun `breach resets when rate returns to range`() {
        val h = Harness()
        h.machine.onHeartRate(120, PostureType.SITTING, now = 0L)
        h.machine.onHeartRate(90, PostureType.SITTING, now = 5_000L)   // 回落，计时清零
        h.machine.onHeartRate(120, PostureType.SITTING, now = 6_000L)
        h.machine.onHeartRate(120, PostureType.SITTING, now = 15_999L) // 距 6s 起点未满 10s
        assertEquals(0, h.alarms.size)
        h.machine.onHeartRate(120, PostureType.SITTING, now = 16_000L)
        assertEquals(1, h.alarms.size)
    }

    @Test
    fun `non stationary posture never alarms and resets breach`() {
        val h = Harness()
        h.machine.onHeartRate(150, PostureType.EXERCISE, now = 0L)
        h.machine.onHeartRate(150, PostureType.EXERCISE, now = 20_000L)
        h.machine.onHeartRate(150, PostureType.UNKNOWN, now = 40_000L)
        assertEquals(0, h.alarms.size)
        // 切回静止后需重新累计满时长
        h.machine.onHeartRate(150, PostureType.SITTING, now = 50_000L)
        h.machine.onHeartRate(150, PostureType.SITTING, now = 59_999L)
        assertEquals(0, h.alarms.size)
        h.machine.onHeartRate(150, PostureType.SITTING, now = 60_000L)
        assertEquals(1, h.alarms.size)
    }

    @Test
    fun `cooldown suppresses repeated alarms until interval passes`() {
        val h = Harness(cooldownMs = 60_000L)
        h.machine.onHeartRate(120, PostureType.SITTING, now = 0L)
        h.machine.onHeartRate(120, PostureType.SITTING, now = 10_000L)
        assertEquals(1, h.alarms.size)
        // 冷却期内持续超限不再报警
        h.machine.onHeartRate(130, PostureType.SITTING, now = 30_000L)
        h.machine.onHeartRate(130, PostureType.SITTING, now = 69_000L)
        assertEquals(1, h.alarms.size)
        // 冷却结束后重新累计满时长再次报警
        h.machine.onHeartRate(130, PostureType.SITTING, now = 70_001L)
        h.machine.onHeartRate(130, PostureType.SITTING, now = 80_001L)
        assertEquals(2, h.alarms.size)
    }

    @Test
    fun `updateThresholds takes effect immediately`() {
        val h = Harness()
        h.machine.updateThresholds(high = 150, low = 40, durationSec = 5)
        h.machine.onHeartRate(140, PostureType.SITTING, now = 0L)
        h.machine.onHeartRate(140, PostureType.SITTING, now = 10_000L)
        assertEquals(0, h.alarms.size) // 140 < 新高限 150
        h.machine.onHeartRate(160, PostureType.SITTING, now = 20_000L)
        h.machine.onHeartRate(160, PostureType.SITTING, now = 25_000L)
        assertEquals(1, h.alarms.size)
        assertEquals(Alarm(160, isHigh = true, threshold = 150), h.alarms[0])
    }
}
