package com.ibra.ollamamobile

import org.json.JSONObject

enum class ChatMode { CHAT, AGENT }

enum class GenerationState {
    IDLE, CONNECTING, GENERATING, RUNNING_TOOL, WAITING_FOR_APPROVAL, APPLYING_EDIT, RESUMING_AGENT, COMPLETED, CANCELLED, FAILED
}

enum class MessageStatus {
    PENDING, STREAMING, COMPLETED, CANCELLED, ERROR
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    // Legacy support or helper
    val isStreaming: Boolean get() = status == MessageStatus.STREAMING
}

data class StreamEvent(
    val type: String,
    val requestId: String,
    val timestamp: Long,
    val payload: String
)

data class PerformanceTelemetry(
    val requestId: String = "",
    val selectedNumCtx: Int = 0,
    val estimatedInputTokens: Int = 0,
    val reservedOutputTokens: Int = 0,
    val performanceMode: String = "",
    val modelSizeClass: String = "",
    val memoryPressure: String = "",
    val keepAlive: String = "",
    val selectionReason: String = "",
    val isWarm: Boolean = false,
    var requestStartedAt: Long = 0L,
    var firstTokenAt: Long = 0L,
    var completedAt: Long = 0L,
    var tokenCount: Int = 0,
    var promptEvalTimeMs: Long = 0L,
    var generationTimeMs: Long = 0L
) {
    val timeToFirstTokenMs: Long get() = if (firstTokenAt > 0 && requestStartedAt > 0) firstTokenAt - requestStartedAt else 0L
    val totalGenerationTimeMs: Long get() = if (completedAt > 0 && requestStartedAt > 0) completedAt - requestStartedAt else 0L
    val tokensPerSecond: Double get() = if (completedAt > firstTokenAt && tokenCount > 0) (tokenCount.toDouble() / (completedAt - firstTokenAt)) * 1000.0 else 0.0
}

data class AppSettings(
    val gatewayUrl: String = "http://192.168.1.2:8765",
    val token: String = "",
    val model: String = "",
    val allowEdits: Boolean = false,
    val chatMode: ChatMode = ChatMode.CHAT,
    val performanceMode: String = "AUTO", // AUTO, FAST, BALANCED, LONG_CONTEXT, CUSTOM
    val contextOverride: Int = 0, // 0 means Auto, or 2048, 4096, 8192
    val outputTokenLimit: Int = 0, // 0 means default
    val threadMode: String = "AUTO", // AUTO, 6, 8, 10, 12
    val keepAliveDuration: String = "30m"
)

data class FileRoot(val name: String, val description: String, val access: String)
data class PendingEdit(val editId: String, val diff: String)

data class PendingApproval(
    val approvalId: String,
    val operation: String,
    val payload: JSONObject
)

