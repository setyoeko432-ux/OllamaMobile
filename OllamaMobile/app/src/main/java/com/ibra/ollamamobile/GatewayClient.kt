package com.ibra.ollamamobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewayClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun requestBuilder(url: String, token: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")

    suspend fun models(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/models", settings.token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                throw IOException(err)
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val arr = JSONObject(body).getJSONArray("models")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        }
    }

    suspend fun getRoots(settings: AppSettings): Pair<List<FileRoot>, Map<String, String>> = withContext(Dispatchers.IO) {
        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/roots", settings.token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
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
    }

    suspend fun getScanStatus(settings: AppSettings, alias: String): String = withContext(Dispatchers.IO) {
        val encodedAlias = java.net.URLEncoder.encode(alias, "UTF-8")
        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/roots/$encodedAlias/scan-status", settings.token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            JSONObject(body).getString("status")
        }
    }

    suspend fun scanRoot(settings: AppSettings, alias: String) = withContext(Dispatchers.IO) {
        val encodedAlias = java.net.URLEncoder.encode(alias, "UTF-8")
        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/roots/$encodedAlias/scan", settings.token)
            .post("{}".toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    fun chatStream(
        settings: AppSettings,
        messages: List<ChatMessage>,
        requestId: String
    ): Call {
        val items = JSONArray()
        messages.forEach {
            items.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", items)
            .put("mode", settings.chatMode.name.lowercase())
            .put("allow_edits", settings.chatMode == ChatMode.AGENT && settings.allowEdits)
            .put("request_id", requestId)
            .put("performance_mode", settings.performanceMode)
            .put("context_override", settings.contextOverride)
            .put("output_token_limit", settings.outputTokenLimit)
            .put("thread_mode", settings.threadMode)
            .put("keep_alive_duration", settings.keepAliveDuration)
            .put("conversation_id", "default_conv") // Can be unique identifier per thread

        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/chat", settings.token)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request)
    }

    fun respondToEditStream(
        settings: AppSettings,
        editId: String,
        approve: Boolean,
        requestId: String
    ): Call {
        val action = if (approve) "approve" else "reject"
        val encodedId = java.net.URLEncoder.encode(editId, "UTF-8")
        val body = JSONObject().put("request_id", requestId)
        
        val request = requestBuilder("${settings.gatewayUrl.trimEnd('/')}/edits/$encodedId/$action", settings.token)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request)
    }

    fun parseEvent(line: String): StreamEvent? {
        if (line.isBlank()) return null
        return try {
            val json = JSONObject(line)
            StreamEvent(
                type = json.optString("type", "error"),
                requestId = json.optString("request_id", ""),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                payload = json.optString("payload", json.optString("text", "")) // Fallback to 'text' for backward compatibility
            )
        } catch (e: Exception) {
            null
        }
    }
}
