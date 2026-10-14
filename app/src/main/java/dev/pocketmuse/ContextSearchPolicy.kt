package dev.pocketmuse

internal object ContextSearchPolicy {
    /** Literal word matching: input never supplies FTS operators, column names, or SQL. */
    fun match(query: String): String? {
        require(query.length <= 300) { "Use a search of at most 300 characters." }
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.distinct().take(9).toList()
        require(words.size <= 8) { "Use at most eight search words." }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "\"$it\"" }
    }
}
