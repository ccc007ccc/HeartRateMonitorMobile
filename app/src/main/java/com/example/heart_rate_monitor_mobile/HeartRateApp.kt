package com.example.heart_rate_monitor_mobile

import android.app.Application
import com.example.heart_rate_monitor_mobile.core.AppContainer
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * 全局 Application。
 *
 * 依赖注入：进程级单例统一由 [AppContainer] 管理（惰性创建，
 * ContentProvider Initializer 早于本类 onCreate 时也可安全取用）。
 *
 * 莫奈取色（Material You 动态取色）：
 * - 当 monet_color_enabled 为 true（默认）时，对所有 Activity 应用动态色彩 overlay，
 *   colorPrimary/colorSurface 等主题色由系统根据壁纸动态生成（需 Android 12+，低版本自动回退到主题固定色）。
 * - 关闭时使用 themes.xml/colors.xml 中定义的固定品牌色。
 *
 * 退出应用隐藏后台：由 [com.example.heart_rate_monitor_mobile.ui.BaseActivity] 统一处理。
 */
class HeartRateApp : Application() {

    val container: AppContainer by lazy { AppContainer.get(this) }

    override fun onCreate() {
        super.onCreate()
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ ->
                container.settings.settings.value.general.monetColorEnabled
            }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(this, options)
    }
}
