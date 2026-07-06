package com.example.heart_rate_monitor_mobile.service.posture

import org.json.JSONObject

/**
 * 单个姿态的校准特征。
 *
 * @param meanX/Y/Z 加速度三轴均值（反映重力方向，即手机朝向）
 * @param stdMagnitude 加速度模长的标准差（反映该姿态的基线噪声）
 * @param sampleCount 采样数
 */
data class PostureFeatures(
    val meanX: Float,
    val meanY: Float,
    val meanZ: Float,
    val stdMagnitude: Float,
    val sampleCount: Int
)

/**
 * 姿态校准数据。
 *
 * 包含静坐、站立两种姿态的特征，以及运动判定阈值。
 * 序列化为 JSON 存储于 SharedPreferences key [posture_calibration_data]。
 *
 * 实时检测时，计算当前窗口特征，用欧氏距离与校准样本匹配：
 * - 距离 < [MATCH_THRESHOLD] 才判定为对应姿态，否则返回 UNKNOWN（避免睡眠误报）
 */
data class PostureCalibration(
    val sitting: PostureFeatures?,
    val standing: PostureFeatures?,
    val motionThreshold: Float = 1.5f,
    val calibratedAt: Long = 0L
) {
    /** 静坐和站立均已采集才算校准完成 */
    fun isComplete(): Boolean = sitting != null && standing != null

    /** 序列化为 JSON 字符串 */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("motion_threshold", motionThreshold)
        obj.put("calibrated_at", calibratedAt)
        sitting?.let { obj.put("sitting", featuresToJson(it)) }
        standing?.let { obj.put("standing", featuresToJson(it)) }
        return obj.toString()
    }

    private fun featuresToJson(f: PostureFeatures): JSONObject = JSONObject().apply {
        put("mean_x", f.meanX)
        put("mean_y", f.meanY)
        put("mean_z", f.meanZ)
        put("std_magnitude", f.stdMagnitude)
        put("sample_count", f.sampleCount)
    }

    companion object {
        /** 欧氏距离匹配阈值（m/s²），距离小于此值才判定为对应姿态 */
        const val MATCH_THRESHOLD = 5.0f

        /** 从 JSON 字符串反序列化，解析失败返回 null */
        fun fromJson(json: String?): PostureCalibration? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                PostureCalibration(
                    sitting = obj.optJSONObject("sitting")?.let { parseFeatures(it) },
                    standing = obj.optJSONObject("standing")?.let { parseFeatures(it) },
                    motionThreshold = obj.optDouble("motion_threshold", 1.5).toFloat(),
                    calibratedAt = obj.optLong("calibrated_at", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseFeatures(o: JSONObject): PostureFeatures = PostureFeatures(
            meanX = o.optDouble("mean_x", 0.0).toFloat(),
            meanY = o.optDouble("mean_y", 0.0).toFloat(),
            meanZ = o.optDouble("mean_z", 0.0).toFloat(),
            stdMagnitude = o.optDouble("std_magnitude", 0.0).toFloat(),
            sampleCount = o.optInt("sample_count", 0)
        )
    }
}
