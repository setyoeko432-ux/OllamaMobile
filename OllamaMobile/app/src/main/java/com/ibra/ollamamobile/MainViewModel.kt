package com.ibra.ollamamobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val client = GatewayClient()
    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models = _models.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()
    
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    
    private val _generationState = MutableStateFlow(GenerationState.IDLE)
    val generationState = _generationState.asStateFlow()

    private val _status = MutableStateFlow("Belum terhubung")
    val status = _status.asStateFlow()
    
    private val _toolActivity = MutableStateFlow("")
    val toolActivity = _toolActivity.asStateFlow()

    private val _roots = MutableStateFlow<List<FileRoot>>(emptyList())
    val roots = _roots.asStateFlow()
    private val _scanStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val scanStatuses = _scanStatuses.asStateFlow()
    private val _pendingEdit = MutableStateFlow<PendingEdit?>(null)
    val pendingEdit = _pendingEdit.asStateFlow()

    private val _telemetry = MutableStateFlow(PerformanceTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private var activeCall: Call? = null
    private var currentRequestId: String? = null

    private fun loadSettings(): AppSettings {
        val modeStr = prefs.getString("mode", ChatMode.CHAT.name)
        val mode = runCatching { ChatMode.valueOf(modeStr ?: ChatMode.CHAT.name) }.getOrDefault(ChatMode.CHAT)
        val allowEdits = if (mode == ChatMode.CHAT) false else prefs.getBoolean("edits", false)
        return AppSettings(
            prefs.getString("url", "http://192.168.1.2:8765") ?: "http://192.168.1.2:8765",
            prefs.getString("token", "") ?: "",
            prefs.getString("model", "") ?: "",
            allowEdits,
            mode,
            prefs.getString("perf_mode", "AUTO") ?: "AUTO",
            prefs.getInt("ctx_override", 0),
            prefs.getInt("out_limit", 0),
            prefs.getString("thread_mode", "AUTO") ?: "AUTO",
            prefs.getString("keep_alive_dur", "30m") ?: "30m"
        )
    }

    fun saveSettings(s: AppSettings) {
        val sanitized = if (s.chatMode == ChatMode.CHAT) s.copy(allowEdits = false) else s
        _settings.value = sanitized
        prefs.edit()
            .putString("url", sanitized.gatewayUrl)
            .putString("token", sanitized.token)
            .putString("model", sanitized.model)
            .putBoolean("edits", sanitized.allowEdits)
            .putString("mode", sanitized.chatMode.name)
            .putString("perf_mode", sanitized.performanceMode)
            .putInt("ctx_override", sanitized.contextOverride)
            .putInt("out_limit", sanitized.outputTokenLimit)
            .putString("thread_mode", sanitized.threadMode)
            .putString("keep_alive_dur", sanitized.keepAliveDuration)
            .apply()
    }


    fun connect() = viewModelScope.launch {
        _status.value = "Menghubungkan…"
        runCatching { client.models(_settings.value) }
            .onSuccess { list ->
                _models.value = list
                val current = _settings.value.model
                val selected = if (current in list) current else list.firstOrNull().orEmpty()
                saveSettings(_settings.value.copy(model = selected))
                _status.value = if (list.isEmpty()) "Terhubung, belum ada model" else "Terhubung"
                fetchRoots()
            }
            .onFailure { _status.value = "Gagal: ${it.message ?: "periksa alamat"}" }
    }

    fun testConnection(s: AppSettings) = viewModelScope.launch {
        _status.value = "Menguji koneksi…"
        runCatching { client.models(s) }
            .onSuccess { list ->
                // Don't save, just update models list if successful
                _models.value = list
                _status.value = if (list.isEmpty()) "Gateway terhubung (tidak ada model)" else "Gateway terhubung"
            }
            .onFailure {
                _status.value = when {
                    it.message?.contains("401") == true -> "Token tidak valid"
                    it.message?.contains("timeout", ignoreCase = true) == true -> "Timeout"
                    else -> "Gagal: ${it.message}"
                }
            }
    }

    fun fetchRoots() = viewModelScope.launch {
        runCatching { client.getRoots(_settings.value) }
            .onSuccess { (list, statuses) ->
                _roots.value = list
                _scanStatuses.value = statuses
            }
    }

    fun scanRoot(alias: String) = viewModelScope.launch {
        if (_scanStatuses.value[alias] == "running") return@launch
        runCatching { client.scanRoot(_settings.value, alias) }
            .onSuccess { 
                pollScanStatus(alias)
            }
            .onFailure {
                val newStatuses = _scanStatuses.value.toMutableMap()
                newStatuses[alias] = "error: ${it.message}"
                _scanStatuses.value = newStatuses
            }
    }

    private fun pollScanStatus(alias: String) = viewModelScope.launch {
        var completed = false
        val startTime = System.currentTimeMillis()
        while (!completed && System.currentTimeMillis() - startTime < 300_000) {
            runCatching { client.getScanStatus(_settings.value, alias) }
                .onSuccess { status ->
                    val newStatuses = _scanStatuses.value.toMutableMap()
                    newStatuses[alias] = status
                    _scanStatuses.value = newStatuses
                    if (status.startsWith("completed") || status.startsWith("error")) {
                        completed = true
                    }
                }
            if (!completed) delay(1000)
        }
    }

    fun respondToEdit(approve: Boolean) {
        val edit = _pendingEdit.value ?: return
        val requestId = currentRequestId ?: return
        
        _pendingEdit.value = null
        _generationState.value = if (approve) GenerationState.APPLYING_EDIT else GenerationState.RESUMING_AGENT
        
        viewModelScope.launch {
            val call = client.respondToEditStream(_settings.value, edit.editId, approve, requestId)
            activeCall = call
            processStream(call, requestId)
        }
    }

    fun clear() {
        stopGeneration()
        _messages.value = emptyList()
        _toolActivity.value = ""
        _pendingEdit.value = null
        _busy.value = false
        _generationState.value = GenerationState.IDLE
    }

    fun stopGeneration() {
        activeCall?.cancel()
        activeCall = null
        currentRequestId = null
        _busy.value = false
        _generationState.value = GenerationState.CANCELLED
        
        val list = _messages.value.toMutableList()
        val lastIdx = list.indexOfLast { it.role == "assistant" }
        if (lastIdx != -1 && list[lastIdx].status == MessageStatus.STREAMING) {
            list[lastIdx] = list[lastIdx].copy(status = MessageStatus.CANCELLED)
            _messages.value = list
        }
    }

    private fun updateAssistantMessage(requestId: String, content: String, status: MessageStatus, error: String? = null) {
        if (requestId != currentRequestId) return
        
        val list = _messages.value.toMutableList()
        val lastIdx = list.indexOfLast { it.role == "assistant" }
        if (lastIdx != -1) {
            list[lastIdx] = list[lastIdx].copy(content = content, status = status, error = error)
            _messages.value = list
        }
    }

    fun regenerateLastResponse() {
        val lastAssistantIdx = _messages.value.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx == -1) return
        
        val userMsgIdx = _messages.value.subList(0, lastAssistantIdx).indexOfLast { it.role == "user" }
        if (userMsgIdx == -1) return
        
        val userContent = _messages.value[userMsgIdx].content
        _messages.value = _messages.value.subList(0, userMsgIdx + 1)
        send(userContent, isRetry = true)
    }

    fun retryLastResponse() {
        val lastIdx = _messages.value.indices.lastOrNull() ?: return
        val lastMsg = _messages.value[lastIdx]
        
        if (lastMsg.role == "assistant") {
            val userMsgIdx = _messages.value.subList(0, lastIdx).indexOfLast { it.role == "user" }
            if (userMsgIdx != -1) {
                val userContent = _messages.value[userMsgIdx].content
                _messages.value = _messages.value.subList(0, userMsgIdx + 1)
                send(userContent, isRetry = true)
            }
        } else if (lastMsg.role == "user") {
            send(lastMsg.content, isRetry = true)
        }
    }

    fun send(text: String, isRetry: Boolean = false) {
        if (text.isBlank() || _busy.value || _settings.value.model.isBlank()) return
        
        stopGeneration()
        
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        _busy.value = true
        _generationState.value = GenerationState.CONNECTING
        _toolActivity.value = ""
        _pendingEdit.value = null

        _telemetry.value = PerformanceTelemetry(
            requestId = requestId,
            requestStartedAt = System.currentTimeMillis()
        )

        val history = if (isRetry) {
            _messages.value
        } else {
            val userMsg = ChatMessage(role = "user", content = text.trim())
            _messages.value + userMsg
        }
        
        val assistantMsg = ChatMessage(role = "assistant", content = "", status = MessageStatus.PENDING)
        _messages.value = history + assistantMsg

        viewModelScope.launch {
            val call = client.chatStream(_settings.value, history, requestId)
            activeCall = call
            processStream(call, requestId)
        }
    }

    private suspend fun processStream(call: Call, requestId: String) = withContext(Dispatchers.IO) {
        val buffer = StringBuilder()
        var lastUpdateTime = 0L
        
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                withContext(Dispatchers.Main) {
                    _generationState.value = GenerationState.FAILED
                    updateAssistantMessage(requestId, buffer.toString(), MessageStatus.ERROR, err)
                    _busy.value = false
                }
                return@withContext
            }

            val source = response.body?.source() ?: throw IOException("Empty body")
            
            withContext(Dispatchers.Main) {
                _generationState.value = GenerationState.GENERATING
                updateAssistantMessage(requestId, "", MessageStatus.STREAMING)
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val event = client.parseEvent(line) ?: continue
                if (event.requestId != requestId) continue

                withContext(Dispatchers.Main) {
                    when (event.type) {
                        "metadata" -> {
                            try {
                                val json = JSONObject(event.payload)
                                val currentTel = _telemetry.value
                                if (currentTel.requestId == requestId) {
                                    _telemetry.value = currentTel.copy(
                                        selectedNumCtx = json.optInt("selected_num_ctx"),
                                        estimatedInputTokens = json.optInt("estimated_input_tokens"),
                                        reservedOutputTokens = json.optInt("reserved_output_tokens"),
                                        performanceMode = json.optString("performance_mode"),
                                        modelSizeClass = json.optString("model_size_class"),
                                        memoryPressure = json.optString("memory_pressure"),
                                        keepAlive = json.optString("keep_alive"),
                                        selectionReason = json.optString("selection_reason"),
                                        isWarm = json.optBoolean("is_warm")
                                    )
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                        "token" -> {
                            val currentTel = _telemetry.value
                            if (currentTel.requestId == requestId) {
                                if (currentTel.firstTokenAt == 0L) {
                                    currentTel.firstTokenAt = System.currentTimeMillis()
                                }
                                currentTel.tokenCount += 1
                            }
                            buffer.append(event.payload)
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 80) {
                                lastUpdateTime = now
                                updateAssistantMessage(requestId, buffer.toString(), MessageStatus.STREAMING)
                            }
                        }
                        "tool_started" -> {
                            _generationState.value = GenerationState.RUNNING_TOOL
                            _toolActivity.value = event.payload
                        }
                        "tool_completed" -> {
                            _generationState.value = GenerationState.GENERATING
                            _toolActivity.value = ""
                        }
                        "approval_required" -> {
                            _generationState.value = GenerationState.WAITING_FOR_APPROVAL
                            val json = JSONObject(event.payload)
                            _pendingEdit.value = PendingEdit(json.getString("edit_id"), json.getString("diff"))
                            _busy.value = false
                        }
                        "edit_applied" -> {
                            _generationState.value = GenerationState.RESUMING_AGENT
                            buffer.append("\n\n[Edit Applied: ${event.payload}]\n\n")
                            updateAssistantMessage(requestId, buffer.toString(), MessageStatus.STREAMING)
                        }
                        "edit_rejected" -> {
                            _generationState.value = GenerationState.RESUMING_AGENT
                            buffer.append("\n\n[Edit Rejected: ${event.payload}]\n\n")
                            updateAssistantMessage(requestId, buffer.toString(), MessageStatus.STREAMING)
                        }
                        "done" -> {
                            val currentTel = _telemetry.value
                            if (currentTel.requestId == requestId) {
                                currentTel.completedAt = System.currentTimeMillis()
                            }
                            _generationState.value = GenerationState.COMPLETED
                            updateAssistantMessage(requestId, buffer.toString(), MessageStatus.COMPLETED)
                            _busy.value = false
                            activeCall = null
                        }
                        "error" -> {
                            _generationState.value = GenerationState.FAILED
                            updateAssistantMessage(requestId, buffer.toString(), MessageStatus.ERROR, event.payload)
                            _busy.value = false
                            activeCall = null
                        }
                    }
                }
            }
            
            // Final flush
            withContext(Dispatchers.Main) {
                if (requestId == currentRequestId && _busy.value) {
                    val currentTel = _telemetry.value
                    if (currentTel.requestId == requestId && currentTel.completedAt == 0L) {
                        currentTel.completedAt = System.currentTimeMillis()
                    }
                    updateAssistantMessage(requestId, buffer.toString(), MessageStatus.COMPLETED)
                    _generationState.value = GenerationState.COMPLETED
                    _busy.value = false
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                if (call.isCanceled()) {
                    _generationState.value = GenerationState.CANCELLED
                    updateAssistantMessage(requestId, buffer.toString(), MessageStatus.CANCELLED)
                } else {
                    _generationState.value = GenerationState.FAILED
                    updateAssistantMessage(requestId, buffer.toString(), MessageStatus.ERROR, e.message)
                }
                _busy.value = false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _generationState.value = GenerationState.FAILED
                updateAssistantMessage(requestId, buffer.toString(), MessageStatus.ERROR, e.message)
                _busy.value = false
            }
        }
    }
}
