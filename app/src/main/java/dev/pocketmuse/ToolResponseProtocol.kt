package dev.pocketmuse

/** Recognize attempted calls separately from accepting the one executable wire format. */
internal object ToolResponseProtocol {
    private val openings by lazy {
        (listOf("tool", "tool_call") + ToolCatalog.definitions.map { it.name })
            .flatMap { listOf("[$it]", "<$it>") }
    }
    private val canonical = Regex("^\\s*\\[tool]([\\s\\S]*?)\\[/tool]\\s*$")

    // Only a leading call is actionable. Mentions and examples inside prose stay ordinary text.
    fun isAttempt(text: String): Boolean {
        val answer = text.trimStart()
        if (answer.isEmpty()) return false
        return openings.any { opening ->
            val stem = opening.dropLast(1)
            when {
                answer.startsWith("$opening(", ignoreCase = true) -> false // Markdown link
                opening.startsWith(answer, ignoreCase = true) -> true // streamed prefix
                !answer.startsWith(stem, ignoreCase = true) -> false
                else -> answer.getOrNull(stem.length)?.let { it == opening.last() || it.isWhitespace() || it == '{' } == true
            }
        }
    }

    /** Alternate, partial and malformed calls must enter repair, never the final-answer path. */
    fun payload(text: String): String? {
        if (!isAttempt(text)) return null
        require(text.length <= 32_000) { "Tool request is too long." }
        return canonical.matchEntire(text)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Malformed tool request. Use [tool] with name and arguments JSON, then [/tool].")
    }
}
