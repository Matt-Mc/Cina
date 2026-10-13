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
        val relevant = enabled.map { skill -> skill to terms.sumOf { word ->
            (if (skill.title.contains(word, true)) 3 else 0) + (if (skill.description.contains(word, true)) 1 else 0)
        } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(1).map { it.first }
        return "Available procedures (use find_skill to load instructions if needed): " +
            enabled.joinToString("; ") { "${it.id}: ${it.title}" } + "\n" + AssistantSkills.prompt(relevant)
    }

    fun toolGroups(request: String): Set<String> {
        val terms = request.lowercase()
        return mapOf(
            "notes" to listOf("note", "grocer", "packing list"), "reminders" to listOf("remind", "nudge"),
            "schedules" to listOf("schedule", "automatic", "every day", "weekly prompt"), "calendar" to listOf("calendar", "event"),
            "alarm" to listOf("alarm", "wake"), "share" to listOf("share"),
            "memory" to listOf("remember", "memory", "memories", "preference"), "files" to listOf("file", "document", "attachment", "pdf"),
            "skills" to listOf("skill", "workflow")
        ).filterValues { words -> words.any { terms.contains(it) } }.keys
    }
}
