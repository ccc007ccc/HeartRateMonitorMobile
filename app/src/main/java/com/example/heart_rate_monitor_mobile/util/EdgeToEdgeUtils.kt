package com.example.heart_rate_monitor_mobile.util

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors

/**
 * Edge-to-edge 布局工具。
 *
 * 透明状态栏 + 导航栏：
 * - AppBarLayout 背景延伸至状态栏下方，与状态栏背景完全统一（消除分层色差）
 * - 内容区域获得底部 padding 避免被导航栏遮挡
 * - 状态栏/导航栏图标根据 colorSurface 亮度自动适配（支持莫奈取色动态变化）
 *
 * 适用于所有使用 CoordinatorLayout + AppBarLayout + 内容视图的二级页面。
 */
object EdgeToEdgeUtils {

    /**
     * 为 Activity 设置 edge-to-edge 布局。
     *
     * @param activity 目标 Activity
     * @param appBar AppBarLayout 视图，其背景将延伸至状态栏下方
     */
    fun setup(activity: AppCompatActivity, appBar: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        adaptSystemBarIcons(activity, appBar)

        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(android.R.id.content)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.setPadding(appBar.paddingLeft, systemBars.top, appBar.paddingRight, 0)
            // 内容区（AppBarLayout 的兄弟节点）获得底部 padding 避免被导航栏遮挡
            (appBar.parent as? ViewGroup)?.let { parent ->
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child !== appBar) {
                        (child as? ViewGroup)?.setClipToPadding(false)
                        child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, systemBars.bottom)
                        break
                    }
                }
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 根据当前 colorSurface 亮度适配状态栏/导航栏图标颜色。
     * colorSurface 在开启莫奈取色时为系统动态生成色，关闭时为主题固定色，
     * 因此本方法对两种模式均生效。
     *
     * @param activity 目标 Activity
     * @param view 用于解析主题属性的任意视图（通常为 AppBarLayout）
     */
    fun adaptSystemBarIcons(activity: AppCompatActivity, view: View) {
        val surfaceColor = MaterialColors.getColor(
            view,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val isLight = ColorUtils.calculateLuminance(surfaceColor) > 0.5
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }
}
