package com.example.heart_rate_monitor_mobile

import org.json.JSONObject

data class Webhook(
    var name: String,
    var url: String,
    var enabled: Boolean = true,
    var body: String = "{\n  \"bpm\": \"{bpm}\"\n}",
    var headers: String = "{\n  \"Content-Type\": \"application/json\"\n}"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("url", url)
            put("enabled", enabled)
            put("body", body)
            put("headers", headers)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Webhook {
            return Webhook(
                name = json.getString("name"),
                url = json.getString("url"),
                enabled = json.getBoolean("enabled"),
                body = json.getString("body"),
                headers = json.getString("headers")
            )
        }
    }
}