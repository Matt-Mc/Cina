package dev.pocketmuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class AssistantTools(private val context: Context, private val store: LocalStore, private val secrets: SecretStore? = null,
                     private val mcp: McpConnections? = null, private val scheduledModelPath: String? = null,
                     private val webSearch: (String) -> String = DuckDuckGoSearch::search) {
    fun specification(webEnabled: Boolean, request: String = "", recent: String = "", hasFiles: Boolean = false, all: Boolean = false): String =
        ToolCatalog.render(ToolCatalog.select(request, recent, hasFiles, webEnabled,
            context.getSharedPreferences("settings", 0).getBoolean("memory", true),
            mcp?.snapshot()?.any { it.connected } == true, scheduledModelPath != null, all), scheduledModelPath != null)

    fun normalize(request: ToolRequest): ToolRequest {
        val a = JSONObject(request.arguments.toString())
        ToolCatalog.get(request.name)?.validate(a) ?: error("Unknown tool: ${request.name}")
        if (request.name in setOf("create_reminder", "reschedule_reminder", "create_scheduled_task")) {
            require(a.optString("when_text").isBlank() || a.optString("when_iso").isBlank()) { "Choose one date and time." }
            val phrase = a.optString("when_text").ifBlank { a.optString("when_iso") }
            a.put("when_iso", ReminderTime.parse(phrase).toString())
            a.remove("when_text")
        }
        return ToolRequest(request.name, a, request.originalCallKey ?: callKey(request))
    }
    fun itemLabel(request: ToolRequest): String? = when (request.name) {
        "edit_note", "delete_note" -> store.notes().firstOrNull { it.id == request.arguments.optLong("id") }?.title
        "complete_reminder", "reschedule_reminder" -> store.reminders().firstOrNull { it.id == request.arguments.optLong("id") }?.title
        else -> null
    }
    fun noteBefore(request: ToolRequest): Note? = if (request.name in setOf("edit_note", "delete_note")) store.notes().firstOrNull { it.id == request.arguments.optLong("id") } else null
    fun scope(chatId: Long, turnId: Long): String = store.executionScope(chatId, turnId)
    fun history(scope: String) = store.toolHistory(scope)

    fun parse(text: String): ToolRequest? {
        val call = ToolResponseProtocol.decode(text) ?: return null
        val arguments = JSONObject(call.arguments)
        try { ToolCatalog.get(call.name)!!.validate(arguments) }
        catch (error: IllegalArgumentException) { throw ToolProtocolException(error.message ?: "Invalid tool arguments.") }
        return ToolRequest(call.name, arguments)
    }
    fun callKey(request: ToolRequest): String {
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        return request.name + ":" + canonical(request.arguments)
    }

    fun needsConfirmation(request: ToolRequest) = ToolCatalog.kind(request.name) == ToolKind.WRITE
    fun describe(request: ToolRequest): String = "${ToolCatalog.get(request.name)?.label ?: request.name}: ${request.arguments}"
    fun resultMessage(result: String): String = runCatching { JSONObject(result).getString("message") }.getOrDefault(result)
    fun succeeded(result: String): Boolean = runCatching { JSONObject(result).getString("status") == "success" }.getOrDefault(false)
    fun failure(error: Exception): String = JSONObject().put("status", "error").put("message", error.message ?: "Action failed.").toString()
    fun confirmedResults(results: List<String>): String? = results.filter(::succeeded).map(::resultMessage)
        .distinct().takeIf { it.isNotEmpty() }?.joinToString("\n\n", "These tool steps completed before the response stopped:\n\n")

    suspend fun execute(chat: Chat, request: ToolRequest, scope: String = "chat:${chat.id}"): String = withContext(Dispatchers.IO) {
        val originalKey = request.originalCallKey ?: callKey(request)
        if (ToolCatalog.kind(request.name) == ToolKind.WRITE) store.toolReceipt(scope, originalKey)?.let { return@withContext it }
        val prepared = normalize(request)
        val definition = ToolCatalog.get(prepared.name) ?: error("Unknown tool.")
        if (scheduledModelPath != null) require(definition.scheduled) { "This tool is unavailable in scheduled tasks." }
        val a = prepared.arguments
        val key = callKey(prepared)
        if (definition.kind == ToolKind.WRITE) store.toolReceipt(scope, key)?.let { return@withContext it }
        if (prepared.name in setOf("edit_note", "delete_note")) {
            val note = store.notes().firstOrNull { it.id == a.getLong("id") } ?: error("Note not found.")
            require(note.version == a.getLong("version")) { "This note changed. Read it again before changing it." }
            if (prepared.name == "edit_note") {
                require(!a.has("body") || store.noteReadFully(scope, note)) { "Read every page with read_note before replacing the body; use append to add text without replacing it." }
                require(!a.has("body") || !a.has("append")) { "Choose body or append, not both." }
                require(listOf("body", "append", "title").any { a.has(it) }) { "Specify what to change." }
            }
        }
        // Keep the original relative-time request and its resolved time under the same receipt.
        // Retrying "in 10 minutes" must not resolve a new timestamp and create a duplicate reminder.
        if (definition.kind == ToolKind.WRITE) store.beginTool(scope, key, listOf(originalKey))?.let { return@withContext it }
        fun required(key: String): String = a.getString(key).trim().also { require(it.isNotBlank()) { "$key is required." } }
        val result = when(prepared.name) {
            "local_find" -> {
                val query = a.getString("query").take(200)
                specification((store.chat(chat.id) ?: chat).web, query, all = query.isBlank())
            }
            "find_skill" -> {
                val query = a.getString("query").take(200)
                val skills = SkillStore(context).read().filter { it.enabled && (it.id.contains(query, true) || it.title.contains(query, true) || it.description.contains(query, true)) }.take(1)
                AssistantSkills.prompt(skills).ifBlank { "No matching enabled procedure." }
            }
            "create_skill" -> {
                val skill = SkillStore(context).create(required("title"), required("description"), required("instructions"))
                "Skill saved: ${skill.title}. Review, edit, enable or remove it in Settings > Skills."
            }
            "search_memories" -> {
                require(context.getSharedPreferences("settings", 0).getBoolean("memory", true)) { "Memory is off. Enable it in You > Memory." }
                val query = a.getString("query").trim()
                require(query.length <= 200) { "Memory query is too long." }
                store.memories().filter { it.fact.contains(query, true) }.take(20)
                    .joinToString("\n") { "#${it.id}: ${it.fact}${if(it.expiresMillis != null && it.expiresMillis <= System.currentTimeMillis()) " [expired historical detail; do not treat as upcoming]" else ""}" }.ifBlank { "No matching saved memories." }
            }
            "save_memory" -> {
                require(context.getSharedPreferences("settings", 0).getBoolean("memory", true)) { "Memory is off. Enable it in You > Memory." }
                val fact = required("fact")
                require(fact.length in 8..180) { "Memory must be 8 to 180 characters." }
                require(!MemorySafety.isCredential(fact)) { "Passwords, tokens and payment credentials cannot be saved as memory." }
                val existing = store.memories().any { it.fact.equals(fact, ignoreCase = true) }
                if (existing) "Already saved in memory: $fact"
                else {
                    val source = store.messages(chat.id).lastOrNull { it.role == "user" }
                    store.addMemory(fact, chat.id, source?.id)
                    require(store.memories().any { it.fact.equals(fact, ignoreCase = true) }) { "Memory could not be saved." }
                    "Saved in memory: $fact"
                }
            }
            "create_note" -> { val title = required("title"); val id = store.addNote(title, required("body")); "Saved note #$id: $title" }
            "list_notes", "search_notes" -> {
                val q = a.optString("query").trim()
                val offset = a.optInt("offset", 0).coerceAtLeast(0)
                store.notes().filter { q.isBlank() || it.title.contains(q, true) || it.body.contains(q, true) }.drop(offset).take(8)
                    .joinToString("\n") { "#${it.id} ${it.title} (version ${it.version}, ${it.body.length} characters): ${it.body.take(300)} [preview; use read_note before editing]" }.ifBlank { "No matching notes." }
            }
            "read_note" -> {
                val note = store.notes().firstOrNull { it.id == a.getLong("id") } ?: error("Note not found.")
                val cursor = a.optInt("cursor", 0)
                require(cursor in 0..note.body.length) { "Invalid cursor. Start at 0." }
                val end = minOf(cursor + 2000, note.body.length)
                store.recordNoteRead(scope, note, cursor, end)
                JSONObject().put("id", note.id).put("title", note.title).put("version", note.version)
                    .put("body", note.body.substring(cursor, end)).put("next_cursor", if(end < note.body.length) end else JSONObject.NULL).toString()
            }
            "edit_note" -> {
                val note = store.notes().firstOrNull { it.id == a.getLong("id") } ?: error("Note not found.")
                require(!a.has("body") || store.noteReadFully(scope, note)) { "Read every page with read_note before replacing the body; use append to add text without replacing it." }
                fun optional(key: String): String? = if (a.has(key) && !a.isNull(key)) a.getString(key) else null
                store.patchNote(note.id, a.getLong("version"), optional("title"), optional("body"), optional("append"))
                "Updated note: ${a.optString("title", note.title)}"
            }
            "delete_note" -> {
                val note = store.notes().firstOrNull { it.id == a.getLong("id") } ?: error("Note not found.")
                require(note.version == a.getLong("version")) { "This note changed. Read it again before deleting." }
                store.deleteNote(note.id); "Deleted note: ${note.title}"
            }
            "create_reminder" -> {
                val title = required("title"); val whenMillis = Instant.parse(required("when_iso")).toEpochMilli()
                require(whenMillis > System.currentTimeMillis()) { "Reminder time must be in the future." }
                val id = store.addReminder(title, whenMillis)
                ReminderReceiver.schedule(context, id, whenMillis)
                "Reminder saved for ${ReminderTime.label(Instant.ofEpochMilli(whenMillis).toString())}: $title"
            }
            "search_reminders" -> {
                val q = a.optString("query").trim(); store.reminders().filter { !it.done && it.title.contains(q, true) }.take(8).joinToString("\n") { "#${it.id} ${it.title}: ${Instant.ofEpochMilli(it.whenMillis)}" }.ifBlank { "No matching reminders." }
            }
            "complete_reminder" -> {
                val id = a.getLong("id"); require(store.reminders().any { it.id == id && !it.done }) { "Open reminder not found." }
                store.completeReminder(id); ReminderReceiver.cancel(context, id); "Reminder #$id completed."
            }
            "reschedule_reminder" -> {
                val id = a.getLong("id"); val title = a.optString("title").ifBlank { store.reminders().firstOrNull { it.id == id }?.title ?: error("Reminder not found.") }; val time = Instant.parse(required("when_iso")).toEpochMilli()
                require(time > System.currentTimeMillis() && store.reminders().any { it.id == id && !it.done }) { "Choose an existing open reminder and a future time." }
                ReminderReceiver.cancel(context, id); store.updateReminder(id, title, time); ReminderReceiver.schedule(context, id, time)
                "$title rescheduled to ${ReminderTime.label(Instant.ofEpochMilli(time).toString())}."
            }
            "search_attachments" -> {
                val q = a.optString("query").trim()
                (if(q.isBlank()) store.attachmentPreview(chat.id) else store.searchAttachments(chat.id, q)).joinToString("\n\n") { "${it.name}, passage ${it.chunk + 1}: ${it.body.take(1200)}" }.ifBlank { "No relevant passages in this chat's attached files." }
            }
            "create_scheduled_task" -> {
                val title = required("title").take(100)
                val prompt = required("prompt")
                val whenMillis = Instant.parse(required("when_iso")).toEpochMilli()
                val repeat = required("repeat")
                require(whenMillis > System.currentTimeMillis() && repeat in listOf("once", "daily", "weekly")) { "Choose a future time and a valid repeat option." }
                val model = scheduledModelPath ?: context.getSharedPreferences("settings", 0).getString("model", null)
                    ?: error("Choose a model before scheduling a task.")
                require(store.models().any { it.path == model }) { "The selected model is unavailable." }
                val id = store.addScheduledTask(title, prompt, model, whenMillis, repeat)
                store.scheduledTask(id)?.let { ScheduledTaskScheduler.schedule(context, it) }
                "Scheduled task #$id: $title at ${Instant.ofEpochMilli(whenMillis)} ($repeat). Android may start it later."
            }
            "list_scheduled_tasks" -> store.scheduledTasks().take(20).joinToString("\n") {
                "#${it.id} ${it.title}: ${if(it.enabled) "next ${Instant.ofEpochMilli(it.nextRunMillis)}" else "paused or finished"} (${it.repeat})"
            }.ifBlank { "No scheduled tasks." }
            "create_calendar_event" -> {
                val title = required("title"); val start = Instant.parse(required("start_iso")).toEpochMilli(); val end = Instant.parse(required("end_iso")).toEpochMilli()
                require(end > start) { "Event end must follow its start." }
                withContext(Dispatchers.Main) { (context as Activity).startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)) }
                "Opened the calendar editor. The user must save the event there."
            }
            "set_alarm" -> {
                val hour = a.getInt("hour"); val minute = a.getInt("minute"); require(hour in 0..23 && minute in 0..59)
                withContext(Dispatchers.Main) { (context as Activity).startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("message", ""))) }
                "Opened the alarm app. The user may need to save the alarm there."
            }
            "share_text" -> {
                val text = required("text")
                withContext(Dispatchers.Main) { (context as Activity).startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share with")) }
                "Opened Android's share sheet. The user chooses where to send it."
            }
            "web_search" -> {
                require((store.chat(chat.id) ?: chat).web) { "Web access is off for this chat." }
                val query = required("query").take(400)
                runCatching { "DuckDuckGo results:\n${webSearch(query)}" }.getOrElse { failure ->
                    val key = secrets?.get("brave") ?: throw failure
                    val connection = (URL("https://api.search.brave.com/res/v1/web/search?q=${android.net.Uri.encode(query)}&count=5").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 15_000; setRequestProperty("X-Subscription-Token", key)
                    }
                    try {
                        require(connection.responseCode == 200) { "Brave search failed: HTTP ${connection.responseCode}" }
                        val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
                        "Brave results (DuckDuckGo was unavailable):\n" + (0 until results.length()).joinToString("\n") { index -> val item = results.getJSONObject(index); "${item.optString("title")} - ${item.optString("url")} - ${item.optString("description")}" }.ifBlank { "No web results." }
                    } finally { connection.disconnect() }
                }
            }
            "mcp_find" -> {
                val query = required("query")
                val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
                mcp?.availableTools().orEmpty().map { tool ->
                    tool to words.count { word -> (tool.name + " " + tool.description + " " + tool.serverId).contains(word, true) }
                }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2).joinToString("\n") { (tool, _) ->
                    "${tool.serverId}/${tool.name}: ${tool.description}; inputSchema=${tool.schema.toString()}"
                }.ifBlank { "No matching connected tools. Connect a service in Settings, or try another search." }
            }
            "mcp_call" -> {
                val server = required("server")
                val name = required("tool")
                val arguments = a.getJSONObject("arguments")
                require(arguments.toString().length <= 12_000) { "Tool arguments are too long." }
                (mcp ?: error("Connected tools are unavailable.")).call(server, name, arguments)
            }
            else -> error("Unknown tool: ${request.name}")
        }
        val outcome = JSONObject().put("status", "success").put("message", result).toString()
        if (definition.kind == ToolKind.WRITE) store.finishTool(scope, key, outcome, listOf(originalKey))
        if (chat.id != 0L) store.log(chat.id, "${definition.label} → ${result.take(200)}")
        outcome
    }
}
