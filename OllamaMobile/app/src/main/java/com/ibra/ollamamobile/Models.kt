package com.ibra.ollamamobile

enum class ChatMode { CHAT, AGENT }

data class ChatMessage(val role: String, val content: String)
data class AppSettings(
    val gatewayUrl: String = "http://192.168.1.2:8765",
    val token: String = "",
    val model: String = "",
    val allowEdits: Boolean = false,
    val chatMode: ChatMode = ChatMode.CHAT
)
