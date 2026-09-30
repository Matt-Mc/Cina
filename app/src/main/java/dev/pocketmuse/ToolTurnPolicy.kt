package dev.pocketmuse

/** Guard calls before execution, including across approval pauses. */
internal object ToolTurnPolicy {
    fun blockReason(key: String, writes: Boolean, executed: List<String>, limit: Int): String? = when {
        executed.size >= limit -> "Action limit reached."
        (writes && key in executed) || key == executed.lastOrNull() -> "Repeated tool call stopped."
        else -> null
    }
}
