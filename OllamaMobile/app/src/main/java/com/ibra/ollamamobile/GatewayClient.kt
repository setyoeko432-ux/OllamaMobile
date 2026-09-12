package com.ibra.ollamamobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GatewayClient {
    private fun connection(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

    suspend fun models(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        val c = connection("${settings.gatewayUrl.trimEnd('/')}/models", settings.token)
        c.requestMethod = "GET"
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).getJSONArray("models")
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    suspend fun chat(
        settings: AppSettings,
        messages: List<ChatMessage>,
        onEvent: (type: String, text: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val c = connection("${settings.gatewayUrl.trimEnd('/')}/chat", settings.token)
        c.requestMethod = "POST"
        c.doOutput = true
        val items = JSONArray()
        messages.forEach { items.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", items)
            .put("mode", settings.chatMode.name.lowercase())
            .put("allow_edits", settings.allowEdits)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        BufferedReader(InputStreamReader(stream)).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val event = JSONObject(line)
                onEvent(event.optString("type", "error"), event.optString("text", ""))
            }
        }
        c.disconnect()
    }
}
