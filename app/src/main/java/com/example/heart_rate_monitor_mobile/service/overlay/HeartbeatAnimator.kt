package com.example.heart_rate_monitor_mobile.service.overlay

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.absoluteValue

/**
 * 按 BPM 节奏缩放目标视图的心跳动画。
 *
 * 抽取自 MainActivity / FloatingWindowService / StatusBarResidentService
 * 三处近乎相同的复制粘贴实现，行为保持一致：
 * - bpm > 30 且处于激活态时按 60000/bpm 周期无限缩放；
 * - BPM 变化超过 50ms 周期差才重建动画（避免频繁重启）；
 * - 停止时平滑复位到 1.0 缩放。
 */
class HeartbeatAnimator(
    private val target: View,
    private val maxScale: Float = 1.2f,
) {
    private var animator: ValueAnimator? = null
    private var currentDuration = 0L
    private val interpolator = AccelerateDecelerateInterpolator()

    /**
     * @param bpm 当前心率
     * @param active 是否应播放动画（动画开关开启 且 设备已连接）
     */
    fun update(bpm: Int, active: Boolean) {
        if (active && bpm > MIN_ANIMATED_BPM) {
            val targetDuration = (60000f / bpm).toLong()
            if (animator == null || (currentDuration - targetDuration).absoluteValue > DURATION_TOLERANCE_MS) {
                currentDuration = targetDuration
                animator?.cancel()
                animator = ValueAnimator.ofFloat(1f, maxScale, 1f).apply {
                    duration = currentDuration
                    interpolator = this@HeartbeatAnimator.interpolator
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener { animation ->
                        val scale = animation.animatedValue as Float
                        target.scaleX = scale
                        target.scaleY = scale
                    }
                    start()
                }
            }
        } else {
            stop()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        currentDuration = 0L
        target.animate().scaleX(1f).scaleY(1f).setDuration(RESET_DURATION_MS).start()
    }

    private companion object {
        const val MIN_ANIMATED_BPM = 30
        const val DURATION_TOLERANCE_MS = 50L
        const val RESET_DURATION_MS = 200L
    }
}
