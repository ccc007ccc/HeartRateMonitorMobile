package com.example.heart_rate_monitor_mobile.domain

/**
 * 设备评测指标计算器（纯 Kotlin，可单元测试）。
 * 供多设备对比工具使用：评估各设备的上报性能与相对主设备的准度差距。
 */

/** 滑动窗口采样率：评估设备每秒实际上报的包数 */
class RollingRate(private val windowMs: Long = 10_000L) {
    private val timestamps = ArrayDeque<Long>()

    /** 记录一个样本并返回当前速率（包/秒） */
    fun onSample(timestampMs: Long): Float {
        timestamps.addLast(timestampMs)
        return rateAt(timestampMs)
    }

    /** 以 [nowMs] 为基准修剪窗口并返回速率（数据停止时随时间衰减归零） */
    fun rateAt(nowMs: Long): Float {
        while (timestamps.isNotEmpty() && nowMs - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size < 2) return 0f
        val spanMs = (timestamps.last() - timestamps.first()).coerceAtLeast(1L)
        return (timestamps.size - 1) * 1000f / spanMs
    }

    fun reset() = timestamps.clear()
}

/**
 * 对比设备相对主设备的准度统计：
 * 每次对比设备出样时，取主设备当前 BPM 求差，累计平均绝对差（MAE）。
 */
class BpmDiffAccumulator {
    private var absDiffSum = 0L
    private var count = 0

    /** 最近一次的实时差值（对比设备 - 主设备），无有效对比时为 null */
    var lastDiff: Int? = null
        private set

    /** 会话平均绝对差（MAE），无数据时为 null */
    val meanAbsDiff: Float?
        get() = if (count == 0) null else absDiffSum.toFloat() / count

    val sampleCount: Int get() = count

    /** 双方 BPM 均有效（>0）时才计入 */
    fun onSample(deviceBpm: Int, primaryBpm: Int) {
        if (deviceBpm <= 0 || primaryBpm <= 0) return
        val diff = deviceBpm - primaryBpm
        lastDiff = diff
        absDiffSum += kotlin.math.abs(diff).toLong()
        count++
    }

    fun reset() {
        absDiffSum = 0
        count = 0
        lastDiff = null
    }
}
