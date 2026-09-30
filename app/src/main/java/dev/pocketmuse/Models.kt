package dev.pocketmuse

data class Chat(val id: Long, val title: String, val web: Boolean, val yolo: Boolean)
data class Message(val id: Long, val chatId: Long, val role: String, val body: String, val time: Long)
data class Note(val id: Long, val title: String, val body: String)
data class Reminder(val id: Long, val title: String, val whenMillis: Long, val done: Boolean, val notifiedAt: Long? = null)
data class ScheduledTask(val id: Long, val title: String, val prompt: String, val modelPath: String,
    val nextRunMillis: Long, val repeat: String, val enabled: Boolean, val lastRunMillis: Long?,
    val lastResult: String?, val lastError: String?)
data class Memory(val id: Long, val fact: String, val sourceChatId: Long? = null, val sourceMessageId: Long? = null)
data class MemorySuggestion(val id: Long, val fact: String, val sourceChatId: Long, val sourceMessageId: Long)
data class AgentTask(val id: Long, val chatId: Long, val goal: String, val status: String, val lastResult: String, val updatedAt: Long)
data class ChatAttachment(val id: Long, val chatId: Long, val name: String, val mimeType: String, val textLength: Int)
data class AttachmentHit(val attachmentId: Long, val name: String, val chunk: Int, val body: String)
data class ModelBenchmark(val modelPath: String, val result: String, val measuredAt: Long)
data class ChatSummary(val chatId: Long, val body: String, val throughMessageId: Long)
data class LocalModel(val id: Long, val name: String, val path: String, val source: String, val bytes: Long)
data class DownloadState(val name: String = "", val received: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String = "")
data class ToolRequest(val name: String, val arguments: org.json.JSONObject)
data class PendingAction(val request: ToolRequest, val originalUserText: String, val depth: Int = 0, val executedCalls: List<String> = emptyList(), val toolResults: List<String> = emptyList())
