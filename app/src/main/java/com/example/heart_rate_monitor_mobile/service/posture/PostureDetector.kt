package com.example.heart_rate_monitor_mobile.service.posture

import kotlin.math.sqrt

/**
 * 姿态检测器。
 *
 * 接收加速度传感器样本，维护一个滑动窗口，每 ~500ms 调用 [classify] 输出当前姿态。
 *
 * 算法分两步：
 * 1. 运动判定：加速度模长标准差 > motionThreshold → EXERCISE
 * 2. 静坐/站立区分：计算窗口各轴均值与校准样本的欧氏距离，距离最小且 < MATCH_THRESHOLD 者胜出；
 *    两者距离都大 → UNKNOWN（非校准姿态，如躺下睡眠，不触发预警）
 *
 * 滞回防抖：最近 5 次分类投票，票数 >= 3 才切换 stablePosture，避免边界抖动。
 */
class PostureDetector {

    /** 滑动窗口大小（~2 秒 @ SENSOR_DELAY_GAME 50Hz） */
    private val windowSize = 100
    private val sampleBuffer = ArrayDeque<FloatArray>(windowSize)
    private val magnitudeBuffer = ArrayDeque<Float>(windowSize)

    private var calibration: PostureCalibration? = null

    /** 滞回防抖：最近 5 次分类结果 */
    private val recentClassifications = ArrayDeque<PostureType>(5)
    private var stablePosture = PostureType.UNKNOWN

    /** 设置校准数据（SharedPreferences 变化时热更新） */
    fun setCalibration(cal: PostureCalibration?) {
        calibration = cal
        reset()
    }

    /** 校准数据是否完整（静坐+站立均已采集） */
    fun isCalibrated(): Boolean = calibration?.isComplete() == true

    /**
     * 接收一个加速度样本。
     * @param x y z 三轴加速度（m/s²）
     */
    fun onSensorSample(x: Float, y: Float, z: Float) {
        if (sampleBuffer.size >= windowSize) {
            sampleBuffer.removeFirst()
            magnitudeBuffer.removeFirst()
        }
        sampleBuffer.addLast(floatArrayOf(x, y, z))
        magnitudeBuffer.addLast(sqrt(x * x + y * y + z * z))
    }

    /**
     * 对当前窗口进行姿态分类。
     * 每 ~500ms 调用一次，不必每样本调。
     */
    fun classify(): PostureType {
        val cal = calibration
        // 样本不足窗口一半 → 数据不足
        if (sampleBuffer.size < windowSize / 2) return PostureType.UNKNOWN
        // 无校准或校准不完整 → 无法区分静坐/站立
        if (cal == null || !cal.isComplete()) return PostureType.UNKNOWN

        // 计算窗口特征
        val meanX = sampleBuffer.map { it[0] }.average().toFloat()
        val meanY = sampleBuffer.map { it[1] }.average().toFloat()
        val meanZ = sampleBuffer.map { it[2] }.average().toFloat()
        val stdMag = computeStd(magnitudeBuffer)

        // 第一步：运动判定
        if (stdMag > cal.motionThreshold) {
            return updateStable(PostureType.EXERCISE)
        }

        // 第二步：静坐/站立欧氏距离匹配
        val sit = cal.sitting!!
        val stand = cal.standing!!
        val distSit = euclidean(meanX, meanY, meanZ, sit.meanX, sit.meanY, sit.meanZ)
        val distStand = euclidean(meanX, meanY, meanZ, stand.meanX, stand.meanY, stand.meanZ)

        val candidate = when {
            distSit < PostureCalibration.MATCH_THRESHOLD && distSit < distStand -> PostureType.SITTING
            distStand < PostureCalibration.MATCH_THRESHOLD && distStand < distSit -> PostureType.STANDING
            else -> PostureType.UNKNOWN  // 距离都大 → 非校准姿态（如躺下睡眠）
        }
        return updateStable(candidate)
    }

    /** 当前稳定的姿态（不经滞回防抖的最近结果） */
    fun currentStablePosture(): PostureType = stablePosture

    /** 清空缓冲区，用于传感器重新注册或校准重新开始 */
    fun reset() {
        sampleBuffer.clear()
        magnitudeBuffer.clear()
        recentClassifications.clear()
        stablePosture = PostureType.UNKNOWN
    }

    /** 滞回防抖：候选加入最近 5 次记录，票数 >= 3 才更新稳定姿态 */
    private fun updateStable(candidate: PostureType): PostureType {
        if (recentClassifications.size >= 5) recentClassifications.removeFirst()
        recentClassifications.addLast(candidate)
        if (recentClassifications.count { it == candidate } >= 3) {
            stablePosture = candidate
        }
        return stablePosture
    }

    private fun computeStd(values: ArrayDeque<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average()
        var sumSq = 0.0
        for (v in values) sumSq += (v - mean) * (v - mean)
        return sqrt(sumSq / values.size).toFloat()
    }

    private fun euclidean(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
