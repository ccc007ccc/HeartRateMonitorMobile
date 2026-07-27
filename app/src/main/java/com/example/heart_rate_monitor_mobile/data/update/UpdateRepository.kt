package com.example.heart_rate_monitor_mobile.data.update

import android.content.Context
import android.util.Log
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.data.settings.SettingsRepository
import com.example.heart_rate_monitor_mobile.domain.VersionComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一次可用更新 */
data class AppUpdate(
    val versionName: String,
    val releaseUrl: String,
    val notes: String,
)

/**
 * GitHub Releases 更新检查（克制策略）：
 * - 仅由 UI 入口触发（磁贴/后台启动不检查）；
 * - 24 小时内只查一次（节流）；
 * - 预发布版不主动提示；用户「不再提示此版本」后该版本永不再弹；
 * - 任何网络/解析失败都静默忽略，绝不打扰用户。
 */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    /** @return 有可提示的新版本时返回，否则 null */
    suspend fun checkForUpdate(force: Boolean = false): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val lastCheck = settings.settings.value.update.lastCheckAtMs
            if (!force && now - lastCheck < MIN_CHECK_INTERVAL_MS) return@withContext null

            val json = fetchLatestRelease() ?: return@withContext null
            settings.set(SettingsKeys.UPDATE_LAST_CHECK_AT, now)

            val tag = json.optString("tag_name")
            if (tag.isEmpty()) return@withContext null
            if (json.optBoolean("prerelease", false)) return@withContext null

            val current = currentVersionName() ?: return@withContext null
            if (!VersionComparator.isNewer(tag, current)) return@withContext null
            if (!force && settings.settings.value.update.skippedVersion == tag) return@withContext null

            AppUpdate(
                versionName = tag,
                releaseUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                notes = json.optString("body").lineSequence().take(NOTES_MAX_LINES)
                    .joinToString("\n").trim(),
            )
        } catch (e: Exception) {
            Log.d(TAG, "检查更新失败（忽略）", e)
            null
        }
    }

    suspend fun skipVersion(versionName: String) {
        settings.set(SettingsKeys.UPDATE_SKIPPED_VERSION, versionName)
    }

    private fun fetchLatestRelease(): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "HeartRateMonitorMobile")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.d(TAG, "拉取 release 信息失败（忽略）", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun currentVersionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        Log.w(TAG, "读取当前版本号失败", e)
        null
    }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/ccc007ccc/HeartRateMonitorMobile/releases/latest"
        const val RELEASES_PAGE = "https://github.com/ccc007ccc/HeartRateMonitorMobile/releases"
        private const val MIN_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val TIMEOUT_MS = 8000
        private const val NOTES_MAX_LINES = 12
    }
}
