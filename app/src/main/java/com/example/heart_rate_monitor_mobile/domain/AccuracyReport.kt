package com.example.heart_rate_monitor_mobile.domain

import kotlin.math.abs

/**
 * 对比设备相对主设备的准度报告（历史会话回放计算，纯 Kotlin 可单测）。
 * 按时间戳最近邻配对（容差内），统计平均绝对差与最大绝对差。
 */
data class AccuracyReport(
    val pairedSamples: Int,
    val meanAbsDiff: Float,
    val maxAbsDiff: Int,
) {
    companion object {
        private const val PAIR_TOLERANCE_MS = 2_000L

        /**
         * @param primary 主设备样本（时间升序，Pair<时间戳ms, bpm>）
         * @param other   对比设备样本（时间升序）
         * @return 无可配对样本时返回 null
         */
        fun compute(primary: List<Pair<Long, Int>>, other: List<Pair<Long, Int>>): AccuracyReport? {
            if (primary.isEmpty() || other.isEmpty()) return null
            var sum = 0L
            var max = 0
            var count = 0
            var pIndex = 0
            for ((ts, bpm) in other) {
                if (bpm <= 0) continue
                // 双指针推进到最近邻
                while (pIndex + 1 < primary.size &&
                    abs(primary[pIndex + 1].first - ts) <= abs(primary[pIndex].first - ts)
                ) {
                    pIndex++
                }
                val (pTs, pBpm) = primary[pIndex]
                if (pBpm <= 0 || abs(pTs - ts) > PAIR_TOLERANCE_MS) continue
                val diff = abs(bpm - pBpm)
                sum += diff
                if (diff > max) max = diff
                count++
            }
            return if (count == 0) null else AccuracyReport(count, sum.toFloat() / count, max)
        }
    }
}
