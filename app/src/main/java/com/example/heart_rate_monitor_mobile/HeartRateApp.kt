package com.example.heart_rate_monitor_mobile

import android.app.Application
import android.content.Context
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * 全局 Application。
 *
 * 莫奈取色（Material You 动态取色）：
 * - 当 app_settings.monet_color_enabled 为 true（默认）时，对所有 Activity 应用动态色彩 overlay，
 *   colorPrimary/colorSurface 等主题色由系统根据壁纸动态生成（需 Android 12+，低版本自动回退到主题固定色）。
 * - 关闭时使用 themes.xml/colors.xml 中定义的固定品牌色。
 */
class HeartRateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { activity, _ ->
                activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getBoolean("monet_color_enabled", true)
            }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(this, options)
    }
}
