package com.ibra.ollamamobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private var client = GatewayClient()
    internal fun setClient(c: GatewayClient) { client = c }
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
    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval = _pendingApproval.asStateFlow()

    private val _telemetry = MutableStateFlow(PerformanceTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<JSONObject>>(emptyList())
    val workspaceFiles = _workspaceFiles.asStateFlow()

    private val _searchResults = MutableStateFlow<List<JSONObject>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private var activeCall: Call? = null
    private var currentRequestId: String? = null
    private var conversationId: String = UUID.randomUUID().toString()
    private var currentAssistantMessageId: String? = null

    private fun loadSettings(): AppSettings {
        val modeStr = prefs.getString("mode", ChatMode.CHAT.name)
        val mode = runCatching { ChatMode.valueOf(modeStr ?: ChatMode.CHAT.name) }.getOrDefault(ChatMode.CHAT)
        return AppSettings(
            gatewayUrl = prefs.getString("url", "http://192.168.1.2:8765") ?: "http://192.168.1.2:8765",
            token = prefs.getString("token", "") ?: "",
            model = prefs.getString("model", "") ?: "",
            allowEdits = prefs.getBoolean("edits", false),
            chatMode = mode,
            performanceMode = prefs.getString("perf_mode", "AUTO") ?: "AUTO",
            contextOverride = prefs.getInt("ctx_override", 0),
            outputTokenLimit = prefs.getInt("out_limit", 0),
            threadMode = prefs.getString("thread_mode", "AUTO") ?: "AUTO",
            keepAliveDuration = prefs.getString("keep_alive_dur", "30m") ?: "30m"
        )
    }

    fun saveSettings(s: AppSettings) {
        val sanitized = if (s.chatMode == ChatMode.CHAT) s.copy(allowEdits = false) else s
        _settings.value = sanitized
        prefs.edit()
            .putString("url", sanitized.gatewayUrl).putString("token", sanitized.token)
            .putString("model", sanitized.model).putBoolean("edits", sanitized.allowEdits)
            .putString("mode", sanitized.chatMode.name).putString("perf_mode", sanitized.performanceMode)
            .putInt("ctx_override", sanitized.contextOverride).putInt("out_limit", sanitized.outputTokenLimit)
            .putString("thread_mode", sanitized.threadMode).putString("keep_alive_dur", sanitized.keepAliveDuration)
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
            .onFailure { _status.value = "Gagal: ${it.message}" }
    }

    fun testConnection(s: AppSettings) = viewModelScope.launch {
        _status.value = "Menguji koneksi…"
        runCatching { client.models(s) }
            .onSuccess { list -> _models.value = list; _status.value = "Gateway terhubung" }
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
            .onSuccess { (list, statuses) -> _roots.value = list; _scanStatuses.value = statuses }
    }

    fun scanRoot(alias: String) = viewModelScope.launch {
        if (_scanStatuses.value[alias] == "running") return@launch
        runCatching { client.scanRoot(_settings.value, alias) }.onSuccess { pollScanStatus(alias) }
    }

    private fun pollScanStatus(alias: String) = viewModelScope.launch {
        var done = false; val start = System.currentTimeMillis()
        while (!done && System.currentTimeMillis() - start < 300_000) {
            runCatching { client.getScanStatus(_settings.value, alias) }
                .onSuccess { status ->
                    val s = _scanStatuses.value.toMutableMap(); s[alias] = status; _scanStatuses.value = s
                    if (status.startsWith("completed") || status.startsWith("error")) done = true
                }
            if (!done) delay(1000)
        }
    }

    fun fetchWorkspace() = viewModelScope.launch {
        runCatching { client.getWorkspace(_settings.value) }
            .onSuccess { _workspaceFiles.value = it }
    }

    fun searchFiles(query: String) = viewModelScope.launch {
        runCatching { client.searchMetadata(_settings.value, query) }
            .onSuccess { _searchResults.value = it }
    }

    fun respondToApproval(approve: Boolean) {
        val approval = _pendingApproval.value ?: return
        val rid = currentRequestId ?: return
        val mid = currentAssistantMessageId ?: return
        val initialContent = _messages.value.find { it.id == mid }?.content ?: ""
        _pendingApproval.value = null; _busy.value = true
        _generationState.value = if (approve) GenerationState.APPLYING_EDIT else GenerationState.RESUMING_AGENT
        viewModelScope.launch {
            val call = client.respondToApprovalStream(_settings.value, approval.approvalId, approve, rid)
            activeCall = call; processStream(call, rid, mid, initialContent)
        }
    }

    fun clear() {
        stopGeneration()
        _messages.value = emptyList(); _toolActivity.value = ""; _pendingEdit.value = null
        _busy.value = false; _generationState.value = GenerationState.IDLE
        conversationId = UUID.randomUUID().toString(); currentAssistantMessageId = null
    }

    fun stopGeneration() {
        activeCall?.cancel(); activeCall = null; currentRequestId = null; _busy.value = false
        _generationState.value = GenerationState.CANCELLED
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" && it.status == MessageStatus.STREAMING }
        if (idx != -1) { list[idx] = list[idx].copy(status = MessageStatus.CANCELLED); _messages.value = list }
    }

    private fun updateAssistantMessage(id: String, content: String, status: MessageStatus, error: String? = null) {
        _messages.update { list -> list.map { if (it.id == id) it.copy(content = content, status = status, error = error) else it } }
    }

    fun regenerateLastResponse() {
        val lastIdx = _messages.value.indexOfLast { it.role == "assistant" }
        if (lastIdx == -1) return
        val userIdx = _messages.value.subList(0, lastIdx).indexOfLast { it.role == "user" }
        if (userIdx == -1) return
        val content = _messages.value[userIdx].content; _messages.value = _messages.value.subList(0, userIdx + 1)
        send(content, isRetry = true)
    }

    fun retryLastResponse() {
        val last = _messages.value.lastOrNull() ?: return
        if (last.role == "assistant") regenerateLastResponse()
        else if (last.role == "user") send(last.content, isRetry = true)
    }

    fun send(text: String, isRetry: Boolean = false) {
        if (text.isBlank() || _busy.value || _settings.value.model.isBlank()) return
        stopGeneration()
        val rid = UUID.randomUUID().toString(); currentRequestId = rid
        _busy.value = true; _generationState.value = GenerationState.CONNECTING
        _telemetry.value = PerformanceTelemetry(requestId = rid, requestStartedAt = System.currentTimeMillis())
        val history = if (isRetry) _messages.value else _messages.value + ChatMessage(role = "user", content = text.trim())
        val assistantMsg = ChatMessage(role = "assistant", content = "", status = MessageStatus.PENDING)
        currentAssistantMessageId = assistantMsg.id; _messages.value = history + assistantMsg
        viewModelScope.launch {
            val call = client.chatStream(_settings.value, history, rid, assistantMsg.id, conversationId)
            activeCall = call; processStream(call, rid, assistantMsg.id)
        }
    }

    private suspend fun processStream(call: Call, rid: String, mid: String, initialContent: String = "") {
        withContext(Dispatchers.IO) {
            val buffer = StringBuilder(initialContent); var lastUpdate = 0L; var gotDone = false
            try {
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string() ?: "HTTP ${resp.code}"
                        withContext(Dispatchers.Main) {
                            _generationState.value = GenerationState.FAILED; _busy.value = false
                            updateAssistantMessage(mid, buffer.toString(), MessageStatus.ERROR, err)
                        }
                        return@withContext
                    }
                    val source = resp.body?.source() ?: throw IOException("Empty body")
                    withContext(Dispatchers.Main) {
                        _generationState.value = GenerationState.GENERATING
                        updateAssistantMessage(mid, initialContent, MessageStatus.STREAMING)
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val event = client.parseEvent(line) ?: continue
                        if (event.requestId != rid) continue
                        withContext(Dispatchers.Main) {
                            when (event.type) {
                                "metadata" -> {
                                    try {
                                        val json = JSONObject(event.payload)
                                        _telemetry.update { if (it.requestId == rid) it.copy(
                                            selectedNumCtx = json.optInt("selected_num_ctx"),
                                            estimatedInputTokens = json.optInt("estimated_input_tokens"),
                                            reservedOutputTokens = json.optInt("reserved_output_tokens"),
                                            performanceMode = json.optString("performance_mode"),
                                            modelSizeClass = json.optString("model_size_class"),
                                            memoryPressure = json.optString("memory_pressure"),
                                            keepAlive = json.optString("keep_alive"),
                                            isWarm = json.optBoolean("is_warm")
                                        ) else it }
                                    } catch (e: Exception) {}
                                }
                                "token" -> {
                                    _telemetry.update { if (it.requestId == rid) { if (it.firstTokenAt == 0L) it.firstTokenAt = System.currentTimeMillis(); it.tokenCount++; it } else it }
                                    buffer.append(event.payload)
                                    if (System.currentTimeMillis() - lastUpdate > 80) { lastUpdate = System.currentTimeMillis(); updateAssistantMessage(mid, buffer.toString(), MessageStatus.STREAMING) }
                                }
                                "metrics" -> {
                                    try {
                                        val json = JSONObject(event.payload)
                                        _telemetry.update { if (it.requestId == rid) it.copy(
                                            promptEvalTimeMs = json.optLong("prompt_eval_duration") / 1_000_000,
                                            generationTimeMs = json.optLong("eval_duration") / 1_000_000,
                                            tokenCount = json.optInt("eval_count")
                                        ) else it }
                                    } catch (e: Exception) {}
                                }
                                "tool_started" -> { _generationState.value = GenerationState.RUNNING_TOOL; _toolActivity.value = event.payload }
                                "tool_completed" -> { _generationState.value = GenerationState.GENERATING; _toolActivity.value = "" }
                                "approval_required" -> {
                                    _generationState.value = GenerationState.WAITING_FOR_APPROVAL; _busy.value = false
                                    val json = JSONObject(event.payload); _pendingEdit.value = PendingEdit(json.getString("edit_id"), json.getString("diff"))
                                }
                                "sensitive_read_approval_required", "copy_to_workspace_approval_required", "workspace_edit_approval_required" -> {
                                    _generationState.value = GenerationState.WAITING_FOR_APPROVAL; _busy.value = false
                                    val json = JSONObject(event.payload)
                                    _pendingApproval.value = PendingApproval(
                                        approvalId = json.getString("approval_id"),
                                        operation = json.getString("operation"),
                                        payload = json
                                    )
                                }
                                "copy_started" -> { _generationState.value = GenerationState.RUNNING_TOOL; _toolActivity.value = "Menyalin file..." }
                                "copy_completed" -> { _toolActivity.value = "Salin berhasil" }
                                "edit_applied", "edit_rejected", "workspace_edit_applied", "approval_rejected" -> {
                                    _generationState.value = GenerationState.RESUMING_AGENT; buffer.append("\n\n[${event.payload}]\n\n"); updateAssistantMessage(mid, buffer.toString(), MessageStatus.STREAMING)
                                }
                                "done" -> {
                                    gotDone = true; _telemetry.update { if (it.requestId == rid) { it.completedAt = System.currentTimeMillis(); it } else it }
                                    _generationState.value = GenerationState.COMPLETED; _busy.value = false; updateAssistantMessage(mid, buffer.toString(), MessageStatus.COMPLETED); activeCall = null
                                }
                                "error" -> { _generationState.value = GenerationState.FAILED; _busy.value = false; updateAssistantMessage(mid, buffer.toString(), MessageStatus.ERROR, event.payload); activeCall = null }
                            }
                        }
                        if (event.type == "approval_required") return@withContext
                    }
                    if (!gotDone && _busy.value) {
                        withContext(Dispatchers.Main) {
                            _generationState.value = GenerationState.FAILED; _busy.value = false; updateAssistantMessage(mid, buffer.toString(), MessageStatus.ERROR, "EOF without done")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (call.isCanceled()) { _generationState.value = GenerationState.CANCELLED; updateAssistantMessage(mid, buffer.toString(), MessageStatus.CANCELLED) }
                    else { _generationState.value = GenerationState.FAILED; updateAssistantMessage(mid, buffer.toString(), MessageStatus.ERROR, e.message) }
                    _busy.value = false
                }
            } finally { if (activeCall == call) activeCall = null }
        }
    }
}
