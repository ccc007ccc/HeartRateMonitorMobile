package com.example.heart_rate_monitor_mobile.service.posture

import com.example.heart_rate_monitor_mobile.R

/**
 * 姿态类型枚举。
 *
 * 每个姿态绑定一个 emoji 字符和一个标签字符串资源，UI 经 getString 取文案。
 * - SITTING 静坐、STANDING 站立：静止状态，会触发心率预警检测
 * - EXERCISE 运动：高心率属正常，不触发预警
 * - UNKNOWN 未检测：数据不足、未校准或非校准姿态（如躺下睡眠），不触发预警
 */
enum class PostureType(val emoji: String, val labelRes: Int) {
    UNKNOWN("❓", R.string.posture_unknown),
    SITTING("🧘", R.string.posture_sitting),
    STANDING("🧍", R.string.posture_standing),
    EXERCISE("🏃", R.string.posture_exercise);

    /** 是否为静止状态（静坐或站立），用于预警状态机判断 */
    val isStationary: Boolean get() = this == SITTING || this == STANDING
}
