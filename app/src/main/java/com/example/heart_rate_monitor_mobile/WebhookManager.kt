package com.example.heart_rate_monitor_mobile

import android.content.Context
import android.util.Log
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

    fun sendAllEnabledWebhooks(heartRate: Int) {
        val webhooks = getWebhooks()
        webhooks.filter { it.enabled }.forEach { webhook ->
            scope.launch {
                sendRequest(webhook, heartRate)
            }
        }
    }

    fun testWebhook(webhook: Webhook, onResult: (String) -> Unit) {
        scope.launch {
            val result = sendRequest(webhook, 88, true)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private suspend fun sendRequest(webhook: Webhook, heartRate: Int, isTest: Boolean = false): String {
        return withContext(Dispatchers.IO) {
            val bpm = heartRate.toString()
            val urlString = webhook.url.replace("{bpm}", bpm)
            val bodyString = webhook.body.replace("{bpm}", bpm)

            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000 // 10s
                connection.readTimeout = 10000 // 10s

                // Set Headers
                try {
                    val headersJson = JSONObject(webhook.headers.replace("{bpm}", bpm))
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


                // Set Body
                connection.doOutput = true
                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(bodyString)
                writer.flush()
                writer.close()

                // Get Response
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

                """
                --- Webhook 测试响应 ---
                名称: ${webhook.name}
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
                webhooks.add(Webhook.fromJson(jsonArray.getJSONObject(i)))
            }
            webhooks
        } catch (e: Exception) {
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

                // Validate JSON
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