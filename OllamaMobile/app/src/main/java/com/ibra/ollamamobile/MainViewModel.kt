package com.ibra.ollamamobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            }
            .onFailure { _status.value = "Gagal: ${it.message ?: "periksa alamat"}" }
    }

    fun clear() { _messages.value = emptyList(); _toolActivity.value = "" }

    fun send(text: String) {
        if (text.isBlank() || _busy.value || _settings.value.model.isBlank()) return
        val base = _messages.value + ChatMessage("user", text.trim())
        _messages.value = base + ChatMessage("assistant", "")
        _busy.value = true
        _toolActivity.value = ""
        viewModelScope.launch {
            runCatching {
                client.chat(_settings.value, base) { type, payload ->
                    viewModelScope.launch {
                        when (type) {
                            "token" -> _messages.value = _messages.value.dropLast(1) +
                                _messages.value.last().copy(content = _messages.value.last().content + payload)
                            "tool" -> _toolActivity.value = payload
                            "error" -> _messages.value = _messages.value.dropLast(1) + ChatMessage("assistant", "Error: $payload")
                        }
                    }
                }
            }.onFailure {
                _messages.value = _messages.value.dropLast(1) + ChatMessage("assistant", "Tidak dapat terhubung: ${it.message}")
            }
            _busy.value = false
        }
    }
}
