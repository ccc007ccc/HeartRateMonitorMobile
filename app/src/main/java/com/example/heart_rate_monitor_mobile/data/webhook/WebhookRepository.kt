package com.example.heart_rate_monitor_mobile.data.webhook

import android.content.Context
import android.util.Log
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.Webhook
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Webhook 配置仓库（取代旧 ui.webhook.WebhookManager）。
 *
 * 相比旧实现的修复：
 * - 配置内存缓存：triggerWebhooks 不再每次心率更新都同步读盘解析 JSON；
 * - URL 安全：仅允许 https://（保存与发送双重校验），防止心率数据明文外发；
 * - GitHub 同步改为"拉取→预览→确认合并"，且同步条目强制 enabled=false，
 *   杜绝上游仓库被入侵时静默把所有用户的数据重定向到攻击者服务器。
 */
class WebhookRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val webhookFile = File(context.filesDir, "config_webhook.json")
    private val mutex = Mutex()

    private val _webhooks = MutableStateFlow<List<Webhook>>(emptyList())
    val webhooks: StateFlow<List<Webhook>> = _webhooks.asStateFlow()

    @Volatile
    private var loaded = false

    /** 已启用 webhook 订阅的触发器集合（配置变更时重算），供 triggerWebhooks 快路径判断 */
    @Volatile
    private var enabledTriggers: Set<WebhookTrigger> = emptySet()

    private fun recomputeEnabledTriggers() {
        enabledTriggers = _webhooks.value
            .filter { it.enabled }
            .flatMapTo(mutableSetOf()) { it.triggers }
    }

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            _webhooks.value = readFromDisk()
            recomputeEnabledTriggers()
            loaded = true
        }
    }

    private fun readFromDisk(): List<Webhook> {
        if (!webhookFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(webhookFile.readText())
            buildList {
                for (i in 0 until jsonArray.length()) {
                    add(Webhook.fromJson(jsonArray.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 Webhook 配置失败", e)
            emptyList()
        }
    }

    suspend fun save(webhooks: List<Webhook>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val jsonArray = JSONArray()
                webhooks.forEach { jsonArray.put(it.toJson()) }
                webhookFile.writeText(jsonArray.toString(4))
                _webhooks.value = webhooks
                recomputeEnabledTriggers()
                loaded = true
            } catch (e: Exception) {
                Log.e(TAG, "保存 Webhook 配置失败", e)
            }
        }
    }

    fun triggerWebhooks(trigger: WebhookTrigger, heartRate: Int = 0, speed: Float = 0f) {
        // 快路径：配置已加载且没有任何已启用的 webhook 订阅该触发器时，
        // 直接返回——避免每个心率样本（约 1Hz）都空起一个 IO 协程做无用过滤
        if (loaded && trigger !in enabledTriggers) return
        scope.launch(Dispatchers.IO) {
            ensureLoaded()
            _webhooks.value
                .filter { it.enabled && it.triggers.contains(trigger) }
                .forEach { webhook ->
                    launch { sendRequest(webhook, heartRate, speed, trigger) }
                }
        }
    }

    fun testWebhook(webhook: Webhook, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = sendRequest(webhook, 88, 15.5f, WebhookTrigger.HEART_RATE_UPDATED, isTest = true)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * 从 GitHub 拉取官方预设，仅返回解析结果供 UI 预览，不写入本地配置。
     */
    suspend fun fetchGithubPresets(): Result<List<Webhook>> = withContext(Dispatchers.IO) {
        try {
            val response = (URL(GITHUB_PRESET_URL).openConnection() as HttpURLConnection).run {
                connectTimeout = 15000
                readTimeout = 15000
                inputStream.bufferedReader().use { it.readText() }
            }
            val jsonArray = JSONArray(response)
            val presets = buildList {
                for (i in 0 until jsonArray.length()) {
                    val webhook = Webhook.fromJson(jsonArray.getJSONObject(i))
                    // 强制禁用：远程下发的配置必须由用户逐条显式启用
                    add(webhook.copy(enabled = false))
                }
            }
            // 基本 schema 校验：预设 URL 也必须是 https
            val invalid = presets.filterNot { isUrlAllowed(it.url) }
            if (invalid.isNotEmpty()) {
                Result.failure(IllegalArgumentException("预设中包含非 https URL：${invalid.joinToString { it.name }}"))
            } else {
                Result.success(presets)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉取 GitHub 预设失败", e)
            Result.failure(e)
        }
    }

    /** 将预设合并进现有配置（按 名称+URL 去重，保留本地已有条目） */
    suspend fun mergePresets(presets: List<Webhook>) {
        ensureLoaded()
        val existing = _webhooks.value
        val existingKeys = existing.map { it.name to it.url }.toSet()
        val merged = existing + presets.filter { (it.name to it.url) !in existingKeys }
        save(merged)
    }

    private suspend fun sendRequest(
        webhook: Webhook,
        heartRate: Int,
        speed: Float,
        trigger: WebhookTrigger,
        isTest: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val bpm = heartRate.toString()
        val speedStr = String.format(Locale.US, "%.1f", speed)

        var urlString = webhook.url
        var bodyString = webhook.body
        var headersString = webhook.headers

        urlString = urlString.replace("{bpm}", bpm).replace("{speed}", speedStr)
        bodyString = bodyString.replace("{bpm}", bpm).replace("{speed}", speedStr)
        headersString = headersString.replace("{bpm}", bpm).replace("{speed}", speedStr)

        if (!isUrlAllowed(urlString)) {
            return@withContext appContext.getString(
                R.string.webhook_send_failed_https, urlString.take(32)
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            try {
                val headersJson = JSONObject(headersString)
                headersJson.keys().forEach { key ->
                    connection.setRequestProperty(key, headersJson.getString(key))
                }
            } catch (e: JSONException) {
                return@withContext appContext.getString(
                    R.string.webhook_send_failed_headers, e.message
                )
            }
            if (connection.getRequestProperty("Content-Type") == null) {
                connection.setRequestProperty("Content-Type", "application/json")
            }
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", "HeartRateMonitorMobile-Webhook")
            }

            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(bodyString) }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            val stream = if (responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""

            val responseTitle = appContext.getString(
                if (isTest) R.string.webhook_response_test_title else R.string.webhook_response_sent_title
            )
            appContext.getString(
                R.string.webhook_response_template,
                responseTitle,
                webhook.name,
                trigger.name,
                responseCode,
                responseMessage,
                responseBody,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Webhook 发送失败: ${webhook.name}", e)
            appContext.getString(R.string.webhook_send_unknown_error, e.message)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "WebhookRepository"
        private const val GITHUB_PRESET_URL =
            "https://raw.githubusercontent.com/ccc007ccc/HeartRateMonitor/main/config_webhook.json"

        /** 仅允许 https，防止心率健康数据明文外发 */
        fun isUrlAllowed(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true)
    }
}
