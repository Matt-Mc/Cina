package dev.pocketmuse

internal data class ParsedToolCall(val name: String, val arguments: Map<String, Any?>)
internal class ToolProtocolException(message: String, val repairable: Boolean = false, val toolName: String? = null) : IllegalArgumentException(message)

/** Known wire formats become data, never executable code. Ambiguous/multiple calls fail closed. */
internal object ToolResponseProtocol {
    private val names by lazy { ToolCatalog.definitions.map { it.name } }
    private val wrappers by lazy { listOf("tool", "tool_call") + names }
    private val openings by lazy { wrappers.flatMap { listOf("[$it]", "<$it>") } }
    private val function = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val actionKeys = Regex("[\"'](?:arguments|parameters|function|tool_calls)[\"']\\s*:")
    private val namedAction by lazy { Regex("[\"'](?:name|tool)[\"']\\s*:\\s*[\"'](?:${names.joinToString("|", transform = Regex::escape)})[\"']") }
    private val jsonPrefixes by lazy {
        listOf("{\"name\":", "{\"tool\":", "{\"function\":", "{\"arguments\":") +
            names.flatMap { listOf("{\"name\":\"$it\"", "{\"tool\":\"$it\"") }
    }

    private fun body(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("```")) {
            val start = trimmed.indexOf('\n')
            if (start < 0) return ""
            return trimmed.substring(start + 1).removeSuffix("```").trim()
        }
        return if (trimmed.startsWith('`')) trimmed.removePrefix("`").removeSuffix("`").trim() else trimmed
    }

    fun isAttempt(text: String): Boolean {
        val answer = body(text)
        if (answer.isEmpty()) return false
        if (openings.any { opening ->
                val stem = opening.dropLast(1)
                !answer.startsWith("$opening(", true) &&
                    (opening.startsWith(answer, true) || (answer.startsWith(stem, true) &&
                        answer.getOrNull(stem.length)?.let { it == opening.last() || it.isWhitespace() || it == '{' } == true))
            }) return true
        val call = function.find(answer)?.groupValues?.get(1)
        if (call != null && (call in names || '_' in call)) return true
        if (answer in names) return true
        if (answer.startsWith('{') || answer.startsWith("[{")) {
            val compact = answer.filterNot(Char::isWhitespace)
            return actionKeys.containsMatchIn(answer) || namedAction.containsMatchIn(answer) || jsonPrefixes.any { it.startsWith(compact) }
        }
        return false
    }

    fun hideWhileStreaming(text: String): Boolean {
        if (isAttempt(text)) return true
        val answer = body(text)
        if (answer.isEmpty()) return text.trimStart().startsWith('`')
        return names.any { it.startsWith(answer, true) }
    }

    fun decode(text: String): ParsedToolCall? {
        if (!isAttempt(text)) return null
        requireProtocol(text.length <= 32_000, "Tool request is too long.")
        val trimmed = text.trim()
        requireProtocol(!trimmed.startsWith("```") || trimmed.endsWith("```"), "Incomplete action code block.")
        requireProtocol(!trimmed.startsWith('`') || trimmed.endsWith('`'), "Incomplete action code span.")
        var answer = body(text)
        var wrapper: String? = null
        val opening = openings.firstOrNull { answer.startsWith(it, true) && !answer.startsWith("$it(", true) }
        if (opening != null) {
            wrapper = opening.substring(1, opening.length - 1)
            val closing = "${opening.first()}/$wrapper${opening.last()}"
            requireProtocol(answer.endsWith(closing, true), "Incomplete or multiple action blocks.")
            answer = answer.substring(opening.length, answer.length - closing.length).trim()
        }
        val intended = wrapper?.takeIf { it in names }
        try {
            function.find(answer)?.let { match ->
                requireProtocol(wrapper == null || wrapper in listOf("tool", "tool_call") || wrapper == match.groupValues[1], "Conflicting action names.")
                val name = match.groupValues[1]
                val definition = ToolCatalog.get(name) ?: throw ToolProtocolException("Unknown tool: $name.")
                return ParsedToolCall(name, ToolLiteralReader(answer.substring(match.value.indexOf('('))).functionArguments(definition.fields.map { it.name }))
            }
            if (intended != null && !answer.startsWith('{')) {
                return ParsedToolCall(intended, ToolLiteralReader("($answer)").functionArguments(ToolCatalog.get(intended)!!.fields.map { it.name }))
            }
            val reader = ToolLiteralReader(answer)
            var value = reader.value(); reader.finish()
            if (value is List<*>) {
                requireProtocol(value.size == 1, "Request one tool at a time; no calls were executed.")
                value = value.single()
            }
            val envelope = asObject(value)
            if (intended != null && envelope.keys.all { key -> ToolCatalog.get(intended)!!.fields.any { it.name == key } }) {
                return ParsedToolCall(intended, envelope)
            }
            val call = if (envelope.containsKey("function")) {
                requireProtocol(envelope.keys.all { it in setOf("function", "type", "id") }, "Unexpected action envelope fields.")
                asObject(envelope["function"])
            } else envelope
            requireProtocol(call.keys.all { it in setOf("name", "tool", "arguments", "parameters") }, "Unexpected action fields.")
            requireProtocol(!(call.containsKey("name") && call.containsKey("tool")), "Conflicting action name fields.")
            requireProtocol(!(call.containsKey("arguments") && call.containsKey("parameters")), "Conflicting argument fields.")
            val reportedName = (call["name"] ?: call["tool"]) as? String
            // The observed web_search wrapper used the local alias "search". No other names are guessed.
            requireProtocol(intended == null || reportedName == null || reportedName == intended || (intended == "web_search" && reportedName == "search"), "Conflicting action names.")
            val name = intended ?: reportedName ?: throw ToolProtocolException("Missing tool name.")
            requireProtocol(name in names, "Unknown tool: $name.")
            var arguments = call["arguments"] ?: call["parameters"] ?: throw ToolProtocolException("Missing action arguments.")
            if (arguments is String) { val argumentReader = ToolLiteralReader(arguments); arguments = argumentReader.value() ?: throw ToolProtocolException("Missing arguments."); argumentReader.finish() }
            return ParsedToolCall(name, asObject(arguments))
        } catch (error: ToolProtocolException) { throw error }
        catch (error: IllegalArgumentException) {
            // Only the old unquoted named-tag format may use model repair. Complete literal formats
            // never ask another model pass to guess missing, duplicate, truncated or invalid values.
            val definition = intended?.let(ToolCatalog::get)
            val fieldNames = Regex("(?:^|,)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]").findAll(answer).map { it.groupValues[1] }.toList()
            val completeStringFields = definition != null && definition.fields.all { it.type == "string" } &&
                definition.fields.filter { it.required }.all { it.name in fieldNames } &&
                (definition.name !in setOf("create_reminder", "create_scheduled_task") || fieldNames.any { it == "when_text" || it == "when_iso" }) &&
                fieldNames.distinct().size == fieldNames.size && fieldNames.all { name -> definition.fields.any { it.name == name } }
            val legacy = completeStringFields && !answer.startsWith('{') && !answer.contains('(') &&
                !error.message.orEmpty().startsWith("Duplicate") && !error.message.orEmpty().startsWith("Incomplete")
            throw ToolProtocolException(error.message ?: "Invalid action format.", legacy, intended)
        }
    }

    private fun asObject(value: Any?): Map<String, Any?> {
        requireProtocol(value is Map<*, *>, "Action arguments must be an object.")
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun requireProtocol(condition: Boolean, message: String) { if (!condition) throw ToolProtocolException(message) }
}
