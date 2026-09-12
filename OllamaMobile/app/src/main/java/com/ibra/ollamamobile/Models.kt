package com.ibra.ollamamobile

enum class ChatMode { CHAT, AGENT }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
data class AppSettings(
    val gatewayUrl: String = "http://192.168.1.2:8765",
    val token: String = "",
    val model: String = "",
    val allowEdits: Boolean = false,
    val chatMode: ChatMode = ChatMode.CHAT
)

data class FileRoot(val name: String, val description: String, val access: String)
data class PendingEdit(val editId: String, val diff: String)
