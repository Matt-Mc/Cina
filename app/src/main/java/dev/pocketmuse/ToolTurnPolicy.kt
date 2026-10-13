package dev.pocketmuse

/** Guard calls before execution, including across approval pauses. */
internal object ToolTurnPolicy {
    fun blockReason(key: String, writes: Boolean, executed: List<String>, limit: Int): String? = when {
        executed.count { ToolCatalog.kind(it.substringBefore(':')) == ToolCatalog.kind(key.substringBefore(':')) } >=
            when (ToolCatalog.kind(key.substringBefore(':'))) { ToolKind.DISCOVERY -> 4; ToolKind.READ -> 8; ToolKind.WRITE -> limit } -> "Action limit reached."
        executed.size >= 20 -> "Turn limit reached."
        (writes && key in executed) || key == executed.lastOrNull() -> "Repeated tool call stopped."
        else -> null
    }
}
