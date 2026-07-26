package com.example.heart_rate_monitor_mobile.domain

import com.example.heart_rate_monitor_mobile.service.posture.PostureType

/**
 * 心率预警状态机（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 从 HeartRateAlarmService 的 inner class 抽出：用时间戳记录高/低限越界起始时刻，
 * 每次心率更新时增量判定。仅静坐/站立姿态（isStationary）触发检测；
 * 报警后进入冷却期，期间不重复判定。
 *
 * @param onAlarm 报警回调（bpm、是否高限、姿态、越界阈值）
 */
class AlarmStateMachine(
    var highThreshold: Int,
    var lowThreshold: Int,
    private var durationMs: Long,
    private var cooldownMs: Long,
    private val onAlarm: (bpm: Int, isHigh: Boolean, posture: PostureType, threshold: Int) -> Unit,
) {
    private var highBreachStart = NEVER
    private var lowBreachStart = NEVER
    private var lastAlarmTime = NEVER

    fun onHeartRate(rate: Int, posture: PostureType, now: Long = System.currentTimeMillis()) {
        // 冷却期内不判定
        if (lastAlarmTime != NEVER && now - lastAlarmTime < cooldownMs) {
            resetBreaches()
            return
        }
        // 仅静止姿态（静坐/站立）触发检测
        if (!posture.isStationary) {
            resetBreaches()
            return
        }
        // 高限检测
        if (rate > highThreshold) {
            if (highBreachStart == NEVER) highBreachStart = now
            if (now - highBreachStart >= durationMs) {
                onAlarm(rate, true, posture, highThreshold)
                lastAlarmTime = now
                resetBreaches()
            }
        } else {
            highBreachStart = NEVER
        }
        // 低限检测
        if (rate < lowThreshold) {
            if (lowBreachStart == NEVER) lowBreachStart = now
            if (now - lowBreachStart >= durationMs) {
                onAlarm(rate, false, posture, lowThreshold)
                lastAlarmTime = now
                resetBreaches()
            }
        } else {
            lowBreachStart = NEVER
        }
    }

    fun updateThresholds(high: Int, low: Int, durationSec: Int) {
        highThreshold = high
        lowThreshold = low
        durationMs = durationSec.toLong() * 1000L
    }

    fun updateCooldown(cooldownMs: Long) {
        this.cooldownMs = cooldownMs
    }

    private fun resetBreaches() {
        highBreachStart = NEVER
        lowBreachStart = NEVER
    }

    private companion object {
        /** "从未发生"哨兵：不能用 0（0 是合法时间戳，测试注入的相对时间常从 0 开始） */
        const val NEVER = -1L
    }
}
