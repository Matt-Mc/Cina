package dev.pocketmuse

/** Bounded data parser, never an evaluator. Supports JSON and literal named function arguments. */
internal class ToolLiteralReader(private val source: String) {
    private var position = 0
    private var depth = 0
    private fun whitespace() { while (source.getOrNull(position)?.isWhitespace() == true) position++ }
    private fun peek(): Char? { whitespace(); return source.getOrNull(position) }
    private fun take(char: Char): Boolean = if (peek() == char) { position++; true } else false
    private fun expect(char: Char) { require(take(char)) { "Expected '$char' in action arguments." } }
    fun finish() { require(peek() == null) { "Extra text or multiple calls after the action." } }

    fun value(): Any? {
        require(++depth <= 12) { "Action arguments are nested too deeply." }
        try {
            return when (peek()) {
                '"', '\'' -> string()
                '{' -> objectValue()
                '[' -> arrayValue()
                else -> {
                    val start = position
                    while (source.getOrNull(position)?.let { !it.isWhitespace() && it !in ",})]" } == true) position++
                    val token = source.substring(start, position)
                    when (token) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> {
                            require(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(token)) { "Only literal values are allowed in action arguments." }
                            token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) { "Invalid number." } }
                        }
                    }
                }
            }
        } finally { depth-- }
    }

    private fun string(): String {
        val quote = peek()!!; position++
        val result = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            if (char == quote) return result.toString()
            if (char != '\\') {
                require(char >= ' ') { "Escape control characters in strings." }
                result.append(char)
            } else {
                require(position < source.length) { "Incomplete string escape." }
                when (val escape = source[position++]) {
                    '"', '\'', '\\', '/' -> result.append(escape)
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'u' -> {
                        require(position + 4 <= source.length) { "Incomplete Unicode escape." }
                        val code = source.substring(position, position + 4).toIntOrNull(16)
                        require(code != null) { "Invalid Unicode escape." }
                        result.append(code.toChar()); position += 4
                    }
                    else -> throw IllegalArgumentException("Invalid string escape.")
                }
            }
        }
        throw IllegalArgumentException("Incomplete string in action arguments.")
    }

    private fun key(): String {
        if (peek() in listOf('"', '\'')) return string()
        val start = position
        while (source.getOrNull(position)?.let { it.isLetterOrDigit() || it == '_' } == true) position++
        return source.substring(start, position).also { require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it)) { "Expected an argument name." } }
    }

    private fun put(target: MutableMap<String, Any?>, name: String, value: Any?) {
        require(name !in target) { "Duplicate argument: $name." }
        require(target.size < 128) { "Too many action arguments." }
        target[name] = value
    }

    private fun objectValue(): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        if (take('}')) return result
        do { val name = key(); expect(':'); put(result, name, value()) } while (take(','))
        expect('}')
        return result
    }

    private fun arrayValue(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        if (take(']')) return result
        do { require(result.size < 128) { "Too many array values." }; result.add(value()) } while (take(','))
        expect(']')
        return result
    }

    /** Positional binding is permitted only for a tool with exactly one declared field. */
    fun functionArguments(fields: List<String>): Map<String, Any?> {
        expect('(')
        if (take(')')) { finish(); return emptyMap() }
        val start = position
        val firstKey = runCatching { key() }.getOrNull()
        val named = firstKey != null && peek() in listOf(':', '=')
        position = start
        val result = if (named) {
            val arguments = linkedMapOf<String, Any?>()
            do {
                val name = key()
                require(take(':') || take('=')) { "Expected ':' or '=' after argument name." }
                put(arguments, name, value())
            } while (take(','))
            arguments
        } else {
            val argument = value()
            if (argument is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                argument as Map<String, Any?>
            } else {
                require(fields.size == 1) { "Use named arguments for this tool." }
                mapOf(fields.single() to argument)
            }
        }
        expect(')'); finish()
        return result
    }
}
