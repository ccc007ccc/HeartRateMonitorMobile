package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.domain.AccuracyReport
import com.example.heart_rate_monitor_mobile.domain.BpmDiffAccumulator
import com.example.heart_rate_monitor_mobile.domain.RollingRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceMetricsTest {

    @Test
    fun `rolling rate reflects samples per second and decays`() {
        val rate = RollingRate(windowMs = 10_000L)
        var result = 0f
        // 1Hz 输入 11 个样本（跨满 10 秒窗口）
        for (i in 0..10) {
            result = rate.onSample(i * 1000L)
        }
        assertEquals(1.0f, result, 0.01f)
        // 停止上报 10 秒后衰减归零
        assertEquals(0f, rate.rateAt(25_000L), 0.0f)
    }

    @Test
    fun `diff accumulator tracks last diff and mae`() {
        val acc = BpmDiffAccumulator()
        assertNull(acc.meanAbsDiff)
        acc.onSample(deviceBpm = 82, primaryBpm = 80)   // +2
        acc.onSample(deviceBpm = 76, primaryBpm = 80)   // -4
        acc.onSample(deviceBpm = 0, primaryBpm = 80)    // 无效，忽略
        assertEquals(-4, acc.lastDiff)
        assertEquals(3.0f, acc.meanAbsDiff!!, 0.01f)
        assertEquals(2, acc.sampleCount)
    }

    @Test
    fun `accuracy report pairs by nearest timestamp within tolerance`() {
        val primary = listOf(0L to 80, 1000L to 81, 2000L to 82, 10_000L to 90)
        val other = listOf(
            100L to 82,     // 配对 80 → 差 2
            1900L to 81,    // 配对 82 → 差 1
            6000L to 99,    // 距最近主样本 4s，超容差，跳过
        )
        val report = AccuracyReport.compute(primary, other)!!
        assertEquals(2, report.pairedSamples)
        assertEquals(1.5f, report.meanAbsDiff, 0.01f)
        assertEquals(2, report.maxAbsDiff)
    }

    @Test
    fun `accuracy report null when nothing pairable`() {
        assertNull(AccuracyReport.compute(emptyList(), listOf(0L to 80)))
        assertNull(AccuracyReport.compute(listOf(0L to 80), listOf(50_000L to 80)))
    }
}
