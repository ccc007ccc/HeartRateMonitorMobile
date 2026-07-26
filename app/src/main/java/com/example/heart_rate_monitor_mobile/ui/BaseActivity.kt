package com.example.heart_rate_monitor_mobile.ui

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.core.AppContainer

/**
 * 所有 Activity 的基类。
 *
 * "退出应用隐藏后台"功能：
 * - 通过 companion object 跟踪当前处于 started 状态的 Activity 数量。
 * - 当最后一个 Activity 进入 onStop（数量归零，应用真正退到后台）且开关开启时，
 *   通过 [ActivityManager.AppTask.setExcludeFromRecents] 将当前任务从「最近任务」列表隐藏，
 *   **不销毁任何 Activity**，因此：
 *   - 任意页面退出都能可靠隐藏（不依赖 root activity）
 *   - 重新进入时保留退出前的页面状态（不会强制跳回首页）
 * - 应用回到前台（[onStart]）时恢复 excludeFromRecents=false。
 * - [suppressHideForExternalLaunch]：启动系统设置、浏览器等外部 Activity 前标记，
 *   防止退出到外部页面时误触发。在 [onStart] 中自动复位。
 */
open class BaseActivity : AppCompatActivity() {

    protected val container: AppContainer by lazy { AppContainer.get(this) }

    companion object {
        /** 当前处于 started 状态的 Activity 数量（仅主线程访问） */
        private var startedCount = 0

        /**
         * 启动外部 Activity（系统设置、浏览器等）前设为 true，
         * 阻止 onStop 中的 hide 误触发。在下次 onStart 自动复位。
         */
        @JvmStatic
        var suppressHideForExternalLaunch = false
    }

    override fun onStart() {
        super.onStart()
        startedCount++
        suppressHideForExternalLaunch = false
        // 回到前台：恢复最近任务可见
        setExcludeFromRecentsFlag(false)
    }

    override fun onStop() {
        super.onStop()
        startedCount--
        if (startedCount <= 0
            && !suppressHideForExternalLaunch
            && container.settings.settings.value.general.hideFromRecentsEnabled
        ) {
            // 应用退到后台：从最近任务隐藏（不销毁 Activity，保留页面状态）
            setExcludeFromRecentsFlag(true)
        }
    }

    private fun setExcludeFromRecentsFlag(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myTaskId = taskId
            for (task in am.appTasks) {
                if (task.taskInfo?.id == myTaskId) {
                    task.setExcludeFromRecents(exclude)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("BaseActivity", "设置最近任务可见性失败", e)
        }
    }
}
