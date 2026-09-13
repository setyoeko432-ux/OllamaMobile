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
            connectTimeout = 10_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

    suspend fun models(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            c = connection("${settings.gatewayUrl.trimEnd('/')}/models", settings.token)
            c.requestMethod = "GET"
            if (c.responseCode != 200) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
                throw Exception(err)
            }
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(body).getJSONArray("models")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        } finally {
            c?.disconnect()
        }
    }

    suspend fun getRoots(settings: AppSettings): Pair<List<FileRoot>, Map<String, String>> = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            c = connection("${settings.gatewayUrl.trimEnd('/')}/roots", settings.token)
            c.requestMethod = "GET"
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val rootsArr = json.getJSONArray("roots")
            val roots = (0 until rootsArr.length()).map {
                val o = rootsArr.getJSONObject(it)
                FileRoot(o.getString("name"), o.getString("description"), o.getString("access"))
            }
            val statusJson = json.getJSONObject("status")
            val statuses = statusJson.keys().asSequence().associateWith { statusJson.getString(it) }
            roots to statuses
        } finally {
            c?.disconnect()
        }
    }

    suspend fun getScanStatus(settings: AppSettings, alias: String): String = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            val encodedAlias = java.net.URLEncoder.encode(alias, "UTF-8")
            c = connection("${settings.gatewayUrl.trimEnd('/')}/roots/$encodedAlias/scan-status", settings.token)
            c.requestMethod = "GET"
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).getString("status")
        } finally {
            c?.disconnect()
        }
    }

    suspend fun scanRoot(settings: AppSettings, alias: String) = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            val encodedAlias = java.net.URLEncoder.encode(alias, "UTF-8")
            c = connection("${settings.gatewayUrl.trimEnd('/')}/roots/$encodedAlias/scan", settings.token)
            c.requestMethod = "POST"
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
        } finally {
            c?.disconnect()
        }
    }

    suspend fun respondToEdit(
        settings: AppSettings,
        editId: String,
        approve: Boolean,
        onEvent: (type: String, text: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            val action = if (approve) "approve" else "reject"
            val encodedId = java.net.URLEncoder.encode(editId, "UTF-8")
            c = connection("${settings.gatewayUrl.trimEnd('/')}/edits/$encodedId/$action", settings.token)
            c.requestMethod = "POST"
            
            if (c.responseCode !in 200..299) {
                val errBody = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val errMsg = try { JSONObject(errBody).getString("error") } catch (e: Exception) { "HTTP ${c.responseCode}: $errBody" }
                throw Exception(errMsg)
            }
            
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val event = JSONObject(line)
                    onEvent(event.optString("type", "error"), event.optString("text", ""))
                }
            }
        } finally {
            c?.disconnect()
        }
    }

    suspend fun chat(
        settings: AppSettings,
        messages: List<ChatMessage>,
        onEvent: (type: String, text: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            c = connection("${settings.gatewayUrl.trimEnd('/')}/chat", settings.token)
            c.requestMethod = "POST"
            c.doOutput = true
            val items = JSONArray()
            messages.forEach { 
                // Don't send internal fields to gateway
                items.put(JSONObject().put("role", it.role).put("content", it.content)) 
            }
            val body = JSONObject()
                .put("model", settings.model)
                .put("messages", items)
                .put("mode", settings.chatMode.name.lowercase())
                .put("allow_edits", settings.chatMode == ChatMode.AGENT && settings.allowEdits)
            
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            
            if (c.responseCode !in 200..299) {
                val errBody = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val errMsg = try {
                    JSONObject(errBody).getString("error")
                } catch (e: Exception) {
                    "HTTP ${c.responseCode}: $errBody"
                }
                throw Exception(errMsg)
            }
            
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val event = JSONObject(line)
                    onEvent(event.optString("type", "error"), event.optString("text", ""))
                }
            }
        } finally {
            c?.disconnect()
        }
    }
}
