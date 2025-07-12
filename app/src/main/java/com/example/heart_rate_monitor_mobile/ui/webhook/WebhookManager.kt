package com.example.heart_rate_monitor_mobile.ui.webhook

import android.content.Context
import android.util.Log
import com.example.heart_rate_monitor_mobile.data.Webhook
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WebhookManager(private val context: Context) {

    private val webhookFile = File(context.filesDir, "config_webhook.json")
    private val scope = CoroutineScope(Dispatchers.IO)
    private val githubUrl = "https://raw.githubusercontent.com/ccc007ccc/HeartRateMonitor/main/config_webhook.json"

    fun triggerWebhooks(trigger: WebhookTrigger, heartRate: Int = 0) {
        val webhooks = getWebhooks()
        webhooks.filter { it.enabled && it.triggers.contains(trigger) }.forEach { webhook ->
            scope.launch {
                sendRequest(webhook, heartRate, trigger)
            }
        }
    }

    fun testWebhook(webhook: Webhook, onResult: (String) -> Unit) {
        scope.launch {
            val result = sendRequest(webhook, 88, WebhookTrigger.HEART_RATE_UPDATED, true)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private suspend fun sendRequest(webhook: Webhook, heartRate: Int, trigger: WebhookTrigger, isTest: Boolean = false): String {
        return withContext(Dispatchers.IO) {
            val bpm = heartRate.toString()
            // 检查触发器是否需要替换 {bpm} 占位符
            val shouldReplaceBpm = trigger == WebhookTrigger.HEART_RATE_UPDATED || trigger == WebhookTrigger.DISCONNECTED
            val urlString = if (shouldReplaceBpm) webhook.url.replace("{bpm}", bpm) else webhook.url
            val bodyString = if (shouldReplaceBpm) webhook.body.replace("{bpm}", bpm) else webhook.body
            val headersString = if (shouldReplaceBpm) webhook.headers.replace("{bpm}", bpm) else webhook.headers


            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                try {
                    val headersJson = JSONObject(headersString)
                    headersJson.keys().forEach { key ->
                        connection.setRequestProperty(key, headersJson.getString(key))
                    }
                } catch (e: JSONException) {
                    return@withContext "发送失败: Headers不是有效的JSON格式: ${e.message}"
                }
                if(connection.getRequestProperty("Content-Type") == null){
                    connection.setRequestProperty("Content-Type", "application/json")
                }
                if(connection.getRequestProperty("User-Agent") == null){
                    connection.setRequestProperty("User-Agent", "HeartRateMonitorMobile-Webhook")
                }

                connection.doOutput = true
                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(bodyString)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                val responseMessage = connection.responseMessage
                val inputStream = if (responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val reader = BufferedReader(InputStreamReader(inputStream))
                val responseBody = reader.readText()
                reader.close()

                val responseTitle = if (isTest) "Webhook 测试响应" else "Webhook 已发送"
                """
                --- $responseTitle ---
                名称: ${webhook.name}
                触发于: ${trigger.name}
                状态码: $responseCode $responseMessage
                响应体:
                $responseBody
                ----------------------
                """.trimIndent()

            } catch (e: Exception) {
                "发送时发生未知错误: ${e.message}"
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun getWebhooks(): MutableList<Webhook> {
        if (!webhookFile.exists()) return mutableListOf()
        return try {
            val jsonString = webhookFile.readText()
            val jsonArray = JSONArray(jsonString)
            val webhooks = mutableListOf<Webhook>()
            for (i in 0 until jsonArray.length()) {
                webhooks.add(Webhook.Companion.fromJson(jsonArray.getJSONObject(i)))
            }
            webhooks
        } catch (e: Exception) {
            Log.e("WebhookManager", "获取Webhooks失败", e)
            mutableListOf()
        }
    }

    fun saveWebhooks(webhooks: List<Webhook>) {
        try {
            val jsonArray = JSONArray()
            webhooks.forEach { jsonArray.put(it.toJson()) }
            webhookFile.writeText(jsonArray.toString(4))
        } catch (e: Exception) {
            Log.e("WebhookManager", "保存Webhooks失败", e)
        }
    }

    fun syncFromGithub(onComplete: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val url = URL(githubUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val response = connection.inputStream.bufferedReader().use { it.readText() }

                JSONArray(response)

                webhookFile.writeText(response)

                withContext(Dispatchers.Main) {
                    onComplete(true, "同步成功！已从GitHub获取最新的官方预设。")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "同步过程中发生错误: ${e.message}")
                }
            }
        }
    }
}