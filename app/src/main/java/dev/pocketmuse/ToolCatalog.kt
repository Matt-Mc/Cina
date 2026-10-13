package dev.pocketmuse

import org.json.JSONObject

internal enum class ToolKind { DISCOVERY, READ, WRITE }
internal data class ToolField(val name: String, val type: String = "string", val required: Boolean = true,
    val max: Int = 2000, val choices: List<String> = emptyList())
internal data class ToolDefinition(val name: String, val label: String, val group: String, val kind: ToolKind,
    val instruction: String, val fields: List<ToolField>, val scheduled: Boolean = false) {
    fun instructionLine(): String = "$name(${fields.joinToString { "${it.name}${if (it.required) "" else "?"}:${if (it.choices.isEmpty()) it.type else it.choices.joinToString("|")}" }}): $instruction"
    fun validate(arguments: JSONObject) {
        require(arguments.keys().asSequence().all { key -> fields.any { it.name == key } }) { "Unknown field for $name. Use its listed fields." }
        fields.forEach { field ->
            if (!arguments.has(field.name) || arguments.isNull(field.name)) {
                require(!field.required) { "${field.name} is required." }; return@forEach
            }
            val value = arguments.get(field.name)
            require(when (field.type) {
                "integer" -> value is Number && value.toDouble() == value.toLong().toDouble()
                "object" -> value is JSONObject
                else -> value is String && value.length <= field.max
            }) { "${field.name} must be ${field.type}${if (field.type == "string") " (max ${field.max} characters)" else ""}." }
            require(field.choices.isEmpty() || value in field.choices) { "${field.name}: choose ${field.choices.joinToString()}." }
            if (value is String && field.required && field.name != "query") require(value.isNotBlank()) { "${field.name} is required." }
            if (value is Number && field.name in setOf("id", "version")) require(value.toLong() > 0) { "${field.name} must be a valid saved item number." }
            if (value is Number && field.name in setOf("cursor", "offset")) require(value.toLong() in 0..Int.MAX_VALUE.toLong()) { "${field.name} must be a nonnegative whole number." }
        }
    }
}

/** One registry for selection, validation, permissions and human-readable action labels. */
internal object ToolCatalog {
    private fun s(name: String, required: Boolean = true, max: Int = 2000) = ToolField(name, required = required, max = max)
    private fun id(name: String = "id", required: Boolean = true) = ToolField(name, "integer", required)
    val definitions = listOf(
        ToolDefinition("local_find", "Find available actions", "discovery", ToolKind.DISCOVERY, "Find local tools. Empty query lists all enabled tools.", listOf(s("query", max = 200))),
        ToolDefinition("find_skill", "Find a procedure", "discovery", ToolKind.DISCOVERY, "Load one enabled procedure for this request.", listOf(s("query", max = 200))),
        ToolDefinition("create_note", "Save note", "notes", ToolKind.WRITE, "Save a requested note. Do not save a plan unless asked.", listOf(s("title", max = 200), s("body", max = 12000)), true),
        ToolDefinition("list_notes", "List notes", "notes", ToolKind.READ, "List note IDs, titles and versions; page with offset.", listOf(id("offset", false)), true),
        ToolDefinition("search_notes", "Search notes", "notes", ToolKind.READ, "Find notes; empty query lists notes. Bodies are previews, not full notes.", listOf(s("query", max = 200)), true),
        ToolDefinition("read_note", "Read note", "notes", ToolKind.READ, "Read a full note in pages. Start cursor=0, then use next_cursor until null. Keep version for edits.", listOf(id(), id("cursor", false)), true),
        ToolDefinition("edit_note", "Update note", "notes", ToolKind.WRITE, "Requires version from read_note. Omitted fields stay unchanged. Use append to add text; replacing body requires reading every page first. Never send body and append together.", listOf(id(), id("version"), s("title", false, 200), s("body", false, 12000), s("append", false, 12000))),
        ToolDefinition("delete_note", "Delete note", "notes", ToolKind.WRITE, "Delete only the requested note, using its current version.", listOf(id(), id("version"))),
        ToolDefinition("create_reminder", "Set reminder", "reminders", ToolKind.WRITE, "Requires title and when_text copied from the user's time phrase. Ask when if missing. The app resolves local dates; do not invent a time.", listOf(s("title", max = 200), s("when_text", false, 200), s("when_iso", false, 100)), true),
        ToolDefinition("search_reminders", "Find reminders", "reminders", ToolKind.READ, "Find open reminders and IDs; empty query lists all open reminders.", listOf(s("query", max = 200)), true),
        ToolDefinition("complete_reminder", "Complete reminder", "reminders", ToolKind.WRITE, "Complete the requested reminder using its ID.", listOf(id())),
        ToolDefinition("reschedule_reminder", "Reschedule reminder", "reminders", ToolKind.WRITE, "Move an existing reminder. Copy the requested time into when_text; omitted title is preserved.", listOf(id(), s("title", false, 200), s("when_text", false, 200), s("when_iso", false, 100))),
        ToolDefinition("create_scheduled_task", "Schedule prompt", "schedules", ToolKind.WRITE, "Run a requested prompt later on this phone. Requires a time and repeat; no web or connected services in background.", listOf(s("title", max = 100), s("prompt"), s("when_text", false, 200), s("when_iso", false, 100), ToolField("repeat", choices = listOf("once", "daily", "weekly"))), true),
        ToolDefinition("list_scheduled_tasks", "List schedules", "schedules", ToolKind.READ, "List scheduled prompts and their state.", emptyList(), true),
        ToolDefinition("search_attachments", "Read attached files", "files", ToolKind.READ, "Search this chat's files; cite file and passage. Empty query returns starting passages.", listOf(s("query", max = 400))),
        ToolDefinition("search_memories", "Find saved details", "memory", ToolKind.READ, "Read saved user facts and IDs; empty query lists recent facts.", listOf(s("query", max = 200))),
        ToolDefinition("save_memory", "Remember detail", "memory", ToolKind.WRITE, "Save only a user-provided fact explicitly requested for future chats; 8–180 characters.", listOf(s("fact", max = 180))),
        ToolDefinition("create_skill", "Save skill", "skills", ToolKind.WRITE, "Save a procedure only when explicitly requested. Include trigger, steps and completion check.", listOf(s("title", max = 60), s("description", max = 180), s("instructions", max = 1200))),
        ToolDefinition("create_calendar_event", "Open calendar event", "calendar", ToolKind.WRITE, "Open an event editor; user must save there. ISO times require offsets.", listOf(s("title", max = 200), s("start_iso", max = 100), s("end_iso", max = 100))),
        ToolDefinition("set_alarm", "Open alarm", "alarm", ToolKind.WRITE, "Open an alarm for requested hour 0–23 and minute 0–59; user may need to save there.", listOf(id("hour"), id("minute"), s("message", false, 200))),
        ToolDefinition("share_text", "Open share sheet", "share", ToolKind.WRITE, "Open Android sharing only when requested; user chooses recipient.", listOf(s("text", max = 12000))),
        ToolDefinition("web_search", "Search the web", "web", ToolKind.READ, "Search when requested or current external facts are needed.", listOf(s("query", max = 400))),
        ToolDefinition("mcp_find", "Find connected actions", "connections", ToolKind.DISCOVERY, "Find exact connected tool names and complete schemas before calling mcp_call.", listOf(s("query", max = 200))),
        ToolDefinition("mcp_call", "Run connected action", "connections", ToolKind.WRITE, "Use an exact discovered server, tool and input schema. Do not guess fields.", listOf(s("server", max = 200), s("tool", max = 200), ToolField("arguments", "object")))
    )
    fun get(name: String) = definitions.firstOrNull { it.name == name }
    fun kind(name: String) = get(name)?.kind ?: ToolKind.WRITE
    fun schema(scheduled: Boolean = false): String = JSONObject().put("oneOf", org.json.JSONArray(
        definitions.filter { !scheduled || it.scheduled }.map { tool ->
            val properties = JSONObject()
            tool.fields.forEach { field -> properties.put(field.name, JSONObject().put("type", field.type).apply {
                if (field.choices.isNotEmpty()) put("enum", org.json.JSONArray(field.choices))
            }) }
            JSONObject().put("type", "object").put("additionalProperties", false)
                .put("required", org.json.JSONArray(listOf("name", "arguments")))
                .put("properties", JSONObject().put("name", JSONObject().put("const", tool.name))
                    .put("arguments", JSONObject().put("type", "object").put("properties", properties)
                        .put("required", org.json.JSONArray(tool.fields.filter { it.required }.map { it.name })).put("additionalProperties", false)))
        })).toString()
    fun select(request: String, recent: String = "", hasFiles: Boolean = false, web: Boolean = false,
        memory: Boolean = true, connected: Boolean = false, scheduled: Boolean = false, all: Boolean = false): List<ToolDefinition> {
        val groups = ResponsePolicy.toolGroups(request + "\n" + recent) + if (hasFiles) setOf("files") else emptySet()
        return definitions.filter { tool ->
            (!scheduled || tool.scheduled) && (memory || tool.group != "memory") &&
                (web || tool.group != "web") && (connected || tool.group != "connections") &&
                (scheduled || all || tool.group in groups || tool.group in setOf("discovery", "web", "connections"))
        }
    }
    fun render(tools: List<ToolDefinition>, scheduled: Boolean = false): String = """
        Request exactly one tool with no other text: [tool]{"name":"tool_name","arguments":{"key":"value"}}[/tool]
        Use only the tools below. Required fields have no ?. Never guess missing required details.
        ${if (scheduled) "This is unattended work. If details are missing, report what is missing in the saved result; do not wait for a reply." else "If required details are missing, ask one focused question."}
        Results contain status and message. Only status=success confirms completion. Never repeat a completed action.
        ${tools.joinToString("\n") { it.instructionLine() }}
    """.trimIndent()
}
