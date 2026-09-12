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

    private fun loadSettings() = AppSettings(
        prefs.getString("url", "http://192.168.1.2:8765")!!,
        prefs.getString("token", "")!!,
        prefs.getString("model", "")!!,
        prefs.getBoolean("edits", false),
        ChatMode.valueOf(prefs.getString("mode", ChatMode.CHAT.name)!!)
    )

    fun saveSettings(s: AppSettings) {
        _settings.value = s
        prefs.edit().putString("url", s.gatewayUrl).putString("token", s.token)
            .putString("model", s.model).putBoolean("edits", s.allowEdits)
            .putString("mode", s.chatMode.name).apply()
    }

    fun connect() = viewModelScope.launch {
        _status.value = "Menghubungkan…"
        runCatching { client.models(_settings.value) }
            .onSuccess { list ->
                _models.value = list
                val selected = _settings.value.model.takeIf { it in list } ?: list.firstOrNull().orEmpty()
                saveSettings(_settings.value.copy(model = selected))
                _status.value = if (list.isEmpty()) "Terhubung, belum ada model" else "Terhubung"
                fetchRoots()
            }
            .onFailure { _status.value = "Gagal: ${it.message ?: "periksa alamat"}" }
    }

    fun fetchRoots() = viewModelScope.launch {
        runCatching { client.getRoots(_settings.value) }
            .onSuccess { (list, statuses) ->
                _roots.value = list
                _scanStatuses.value = statuses
            }
    }

    fun scanRoot(alias: String) = viewModelScope.launch {
        runCatching { client.scanRoot(_settings.value, alias) }
            .onSuccess { fetchRoots() }
    }

    fun respondToEdit(approve: Boolean) = viewModelScope.launch {
        val edit = _pendingEdit.value ?: return@launch
        _busy.value = true
        runCatching { client.respondToEdit(_settings.value, edit.editId, approve) }
            .onSuccess { msg ->
                _messages.value = _messages.value + ChatMessage(role = "assistant", content = "Hasil edit: $msg")
                _pendingEdit.value = null
            }
            .onFailure {
                _messages.value = _messages.value + ChatMessage(role = "assistant", content = "Gagal edit: ${it.message}")
                _pendingEdit.value = null
            }
        _busy.value = false
    }

    fun clear() {
        _messages.value = emptyList()
        _toolActivity.value = ""
        _pendingEdit.value = null
    }

    private fun updateLastMessage(content: String, isStreaming: Boolean, error: String? = null) {
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant") {
            list[list.size - 1] = list.last().copy(content = content, isStreaming = isStreaming, error = error)
            _messages.value = list
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _busy.value || _settings.value.model.isBlank()) return
        val userMsg = ChatMessage(role = "user", content = text.trim())
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        val base = _messages.value + userMsg
        _messages.value = base + assistantMsg
        _busy.value = true
        _toolActivity.value = ""
        _pendingEdit.value = null

        var currentContent = ""
        var lastUpdate = 0L

        viewModelScope.launch {
            runCatching {
                client.chat(_settings.value, base) { type, payload ->
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
            }.onFailure {
                updateLastMessage(currentContent, false, it.message)
            }
            _busy.value = false
        }
    }
}
