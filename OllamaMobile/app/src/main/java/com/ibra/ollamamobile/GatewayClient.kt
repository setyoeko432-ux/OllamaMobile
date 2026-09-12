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
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).getJSONArray("models")
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    suspend fun getRoots(settings: AppSettings): Pair<List<FileRoot>, Map<String, String>> = withContext(Dispatchers.IO) {
        val c = connection("${settings.gatewayUrl.trimEnd('/')}/roots", settings.token)
        c.requestMethod = "GET"
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val rootsArr = json.getJSONArray("roots")
        val roots = (0 until rootsArr.length()).map {
            val o = rootsArr.getJSONObject(it)
            FileRoot(o.getString("name"), o.getString("description"), o.getString("access"))
        }
        val statusJson = json.getJSONObject("status")
        val statuses = statusJson.keys().asSequence().associateWith { statusJson.getString(it) }
        roots to statuses
    }

    suspend fun scanRoot(settings: AppSettings, alias: String) = withContext(Dispatchers.IO) {
        val c = connection("${settings.gatewayUrl.trimEnd('/')}/roots/$alias/scan", settings.token)
        c.requestMethod = "POST"
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
    }

    suspend fun respondToEdit(settings: AppSettings, editId: String, approve: Boolean): String = withContext(Dispatchers.IO) {
        val action = if (approve) "approve" else "reject"
        val c = connection("${settings.gatewayUrl.trimEnd('/')}/edits/$editId/$action", settings.token)
        c.requestMethod = "POST"
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val res = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(res)
        if (c.responseCode in 200..299) json.getString("message")
        else throw Exception(json.optString("error", "Unknown error"))
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
        if (c.responseCode !in 200..299) {
            val errBody = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val errMsg = try {
                JSONObject(errBody).getString("error")
            } catch (e: Exception) {
                "HTTP ${c.responseCode}: $errBody"
            }
            throw Exception(errMsg)
        }
        BufferedReader(InputStreamReader(c.inputStream)).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val event = JSONObject(line)
                onEvent(event.optString("type", "error"), event.optString("text", ""))
            }
        }
        c.disconnect()
    }
}
