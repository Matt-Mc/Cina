package dev.pocketmuse

internal enum class ResponseMode { AUTO, QUICK, DEEP }

internal object ResponsePolicy {
    fun resolve(mode: ResponseMode, request: String, goal: Boolean = false): ResponseMode {
        if (mode != ResponseMode.AUTO) return mode
        val complex = Regex("\\b(compare|analy[sz]e|tradeoffs?|research|strategy|debug|step.by.step|detailed)\\b", RegexOption.IGNORE_CASE)
        return if (goal || request.length > 600 || complex.containsMatchIn(request)) ResponseMode.DEEP else ResponseMode.QUICK
    }

    fun skills(skills: List<AssistantSkill>, request: String): String {
        val terms = request.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val enabled = skills.filter { it.enabled }
        val relevant = enabled.filter { skill ->
            terms.any { word -> "${skill.title} ${skill.description}".contains(word, true) }
        }.take(2)
        return "Available procedures (use find_skill to load instructions if needed): " +
            enabled.joinToString("; ") { "${it.id}: ${it.title}" } + "\n" + AssistantSkills.prompt(relevant)
    }

    fun tools(specification: String, request: String): String {
        val terms = request.lowercase()
        val groups = listOf(
            "note" to listOf("note", "grocer"), "reminder" to listOf("remind"),
            "scheduled_task" to listOf("schedule", "automatic"), "calendar" to listOf("calendar", "event"),
            "alarm" to listOf("alarm", "wake"), "share_text" to listOf("share"),
            "memory" to listOf("remember", "memory", "preference"), "attachment" to listOf("file", "document", "attachment"),
            "skill" to listOf("skill", "workflow")
        ).filter { (_, words) -> words.any { terms.contains(it) } }.map { it.first }
        return specification.lineSequence().flatMap { line ->
            if (Regex("^\\s*[a-z_]+\\(").containsMatchIn(line)) line.split("; ").asSequence() else sequenceOf(line)
        }.filter { line ->
            !Regex("^\\s*[a-z_]+\\(").containsMatchIn(line) ||
                line.contains("web_search(") || line.contains("mcp_find(") || line.contains("find_skill(") || line.contains("local_find(") ||
                groups.any { line.contains(it) }
        }.joinToString("\n")
    }
}
