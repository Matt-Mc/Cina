package dev.pocketmuse

data class Chat(val id: Long, val title: String, val web: Boolean, val yolo: Boolean, val archived: Boolean = false)
data class Message(val id: Long, val chatId: Long, val role: String, val body: String, val time: Long)
data class Note(val id: Long, val title: String, val body: String, val version: Long = 1)
data class Reminder(val id: Long, val title: String, val whenMillis: Long, val done: Boolean, val notifiedAt: Long? = null)
data class ScheduledTask(val id: Long, val title: String, val prompt: String, val modelPath: String,
    val nextRunMillis: Long, val repeat: String, val enabled: Boolean, val lastRunMillis: Long?,
    val lastResult: String?, val lastError: String?, val running: Boolean = false)
data class Memory(val id: Long, val fact: String, val sourceChatId: Long? = null, val sourceMessageId: Long? = null, val kind: String = "durable", val expiresMillis: Long? = null)
data class MemorySuggestion(val id: Long, val fact: String, val sourceChatId: Long, val sourceMessageId: Long, val kind: String = "durable", val expiresMillis: Long? = null, val replacesId: Long? = null)
data class AgentTask(val id: Long, val chatId: Long, val goal: String, val status: String, val lastResult: String, val updatedAt: Long)
data class ChatAttachment(val id: Long, val chatId: Long, val name: String, val mimeType: String, val textLength: Int)
data class AttachmentHit(val attachmentId: Long, val name: String, val chunk: Int, val body: String)
data class ModelBenchmark(val modelPath: String, val result: String, val measuredAt: Long)
data class ChatSummary(val chatId: Long, val body: String, val throughMessageId: Long)
data class LocalModel(val id: Long, val name: String, val path: String, val source: String, val bytes: Long)
data class DownloadState(val name: String = "", val received: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String = "")
data class ToolRequest(val name: String, val arguments: org.json.JSONObject, internal val originalCallKey: String? = null)
data class PendingAction(val request: ToolRequest, val originalUserText: String, val depth: Int = 0, val executedCalls: List<String> = emptyList(), val toolResults: List<String> = emptyList())

data class TurnRecord(val id: Long, val chatId: Long, val request: String, val modelPath: String, val mode: String,
    val status: String, val error: String, val pending: PendingAction? = null, val scope: String = "turn:$id", val attempts: Int = 1)
