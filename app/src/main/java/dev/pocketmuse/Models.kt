package dev.pocketmuse

data class Chat(val id: Long, val title: String, val web: Boolean, val yolo: Boolean)
data class Message(val id: Long, val chatId: Long, val role: String, val body: String, val time: Long)
data class Note(val id: Long, val title: String, val body: String)
data class Reminder(val id: Long, val title: String, val whenMillis: Long, val done: Boolean)
data class ScheduledTask(val id: Long, val title: String, val prompt: String, val modelPath: String,
    val nextRunMillis: Long, val repeat: String, val enabled: Boolean, val lastRunMillis: Long?,
    val lastResult: String?, val lastError: String?)
data class Memory(val id: Long, val fact: String)
data class LocalModel(val id: Long, val name: String, val path: String, val source: String, val bytes: Long)
data class DownloadState(val name: String = "", val received: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String = "")
data class ToolRequest(val name: String, val arguments: org.json.JSONObject)
data class PendingAction(val request: ToolRequest, val originalUserText: String, val depth: Int = 0)
