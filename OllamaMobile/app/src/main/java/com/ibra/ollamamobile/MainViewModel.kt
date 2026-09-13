package com.ibra.ollamamobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    private var chatJob: kotlinx.coroutines.Job? = null

    private fun loadSettings(): AppSettings {
        val modeStr = prefs.getString("mode", ChatMode.CHAT.name)
        val mode = runCatching { ChatMode.valueOf(modeStr ?: ChatMode.CHAT.name) }.getOrDefault(ChatMode.CHAT)
        val allowEdits = if (mode == ChatMode.CHAT) false else prefs.getBoolean("edits", false)
        return AppSettings(
            prefs.getString("url", "http://192.168.1.2:8765") ?: "http://192.168.1.2:8765",
            prefs.getString("token", "") ?: "",
            prefs.getString("model", "") ?: "",
            allowEdits,
            mode
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
                _models.value = list
                _status.value = if (list.isEmpty()) "Gateway terhubung (tidak ada model)" else "Gateway terhubung"
            }
            .onFailure {
                _status.value = when {
                    it.message?.contains("401") == true -> "Token tidak valid"
                    it.message?.contains("Timeout") == true -> "Timeout"
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
        while (!completed && System.currentTimeMillis() - startTime < 300_000) { // 5 min timeout
            runCatching { client.getScanStatus(_settings.value, alias) }
                .onSuccess { status ->
                    val newStatuses = _scanStatuses.value.toMutableMap()
                    newStatuses[alias] = status
                    _scanStatuses.value = newStatuses
                    if (status.startsWith("completed") || status.startsWith("error")) {
                        completed = true
                    }
                }
            if (!completed) kotlinx.coroutines.delay(1000)
        }
    }

    fun respondToEdit(approve: Boolean) {
        val edit = _pendingEdit.value ?: return
        _busy.value = true
        _pendingEdit.value = null
        
        chatJob = viewModelScope.launch {
            try {
                processChatStream { onEvent ->
                    client.respondToEdit(_settings.value, edit.editId, approve, onEvent)
                }
            } catch (e: Exception) {
                updateLastMessage(_messages.value.lastOrNull()?.content ?: "", false, e.message)
            } finally {
                _busy.value = false
                _toolActivity.value = ""
            }
        }
    }

    fun clear() {
        stopGeneration()
        _messages.value = emptyList()
        _toolActivity.value = ""
        _pendingEdit.value = null
        _busy.value = false
    }

    fun stopGeneration() {
        chatJob?.cancel()
        chatJob = null
        _busy.value = false
        _toolActivity.value = ""
        // Mark last message as not streaming if it was
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant" && list.last().isStreaming) {
            list[list.size - 1] = list.last().copy(isStreaming = false)
            _messages.value = list
        }
    }

    private fun updateLastMessage(content: String, isStreaming: Boolean, error: String? = null) {
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant") {
            list[list.size - 1] = list.last().copy(content = content, isStreaming = isStreaming, error = error)
            _messages.value = list
        }
    }

    fun regenerateLastResponse() {
        val lastAssistantIdx = _messages.value.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx == -1) return
        
        // Check if there's a user message before it
        val userMsgIdx = _messages.value.subList(0, lastAssistantIdx).indexOfLast { it.role == "user" }
        if (userMsgIdx == -1) return
        
        val userContent = _messages.value[userMsgIdx].content
        
        // Keep history up to that user message
        val newHistory = _messages.value.subList(0, userMsgIdx + 1)
        _messages.value = newHistory
        
        send(userContent, isRetry = true)
    }

    fun retryLastResponse() {
        val lastIdx = _messages.value.indices.lastOrNull() ?: return
        val lastMsg = _messages.value[lastIdx]
        
        if (lastMsg.role == "assistant") {
            // Find preceding user message
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
        
        _busy.value = true
        _toolActivity.value = ""
        _pendingEdit.value = null

        val history = if (isRetry) {
            _messages.value
        } else {
            val userMsg = ChatMessage(role = "user", content = text.trim())
            _messages.value + userMsg
        }
        
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        _messages.value = history + assistantMsg

        chatJob = viewModelScope.launch {
            try {
                processChatStream { onEvent ->
                    client.chat(_settings.value, history, onEvent)
                }
            } catch (e: Exception) {
                updateLastMessage(_messages.value.lastOrNull()?.content ?: "", false, e.message)
            } finally {
                _busy.value = false
                _toolActivity.value = ""
            }
        }
    }

    private suspend fun processChatStream(block: suspend ((type: String, text: String) -> Unit) -> Unit) {
        var currentContent = ""
        var lastUpdate = 0L
        
        block { type, payload ->
            when (type) {
                "token" -> {
                    currentContent += payload
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 100) {
                        lastUpdate = now
                        updateLastMessage(currentContent, true)
                    }
                }
                "tool" -> _toolActivity.value = payload
                "edit" -> {
                    val json = JSONObject(payload)
                    _pendingEdit.value = PendingEdit(json.getString("edit_id"), json.getString("diff"))
                }
                "error" -> {
                    updateLastMessage(currentContent, false, payload)
                }
                "done" -> {
                    updateLastMessage(currentContent, false)
                }
            }
        }
        // Final flush
        updateLastMessage(currentContent, false)
    }
}
