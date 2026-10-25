package dev.pocketmuse

internal enum class ResponseMode { AUTO, QUICK, DEEP }

internal object ResponsePolicy {
    fun workFocused(request: String): Boolean = Regex("\\b(local (action )?plan|action plan|plan for (my )?approval|read_work|work_id)\\b",RegexOption.IGNORE_CASE).containsMatchIn(request)
    fun workTools(request: String): Set<String> = buildSet {
        addAll(listOf("list_work","read_work","local_find","find_skill"))
        fun mentions(pattern: String)=Regex(pattern,RegexOption.IGNORE_CASE).containsMatchIn(request.substringBefore("Current read_work result:"))
        if(mentions("\\b(draft|create|new|build)\\b")) addAll(listOf("draft_work_step","append_work_step"))
        if(mentions("\\b(append|add)\\b")) add("append_work_step")
        if(mentions("\\b(schedule|start|later)\\b")) add("schedule_work")
        if(mentions("\\b(pause|cancel|resume|stop)\\b")) add("manage_work")
    }
    fun resolve(mode: ResponseMode, request: String, goal: Boolean = false, contextChars: Long = 0): ResponseMode {
        if (mode != ResponseMode.AUTO) return mode
        val complex = Regex("\\b(compare|analy[sz]e|tradeoffs?|research|strategy|debug|step.by.step|detailed|comprehensive|multi.step)\\b", RegexOption.IGNORE_CASE)
        val substantial = Regex("^(?:(?:please|can you|could you|help me)\\s+)*(?:build|design|develop|organize|plan|evaluate)\\b", RegexOption.IGNORE_CASE)
        val actions = Regex("\\b(search|find|read|save|create|send|schedule|remind|update|delete|summari[sz]e)\\b", RegexOption.IGNORE_CASE)
            .findAll(request).map { it.value.lowercase() }.toSet().size
        val multipleSteps = actions >= 2 && Regex("\\b(and|then|also|after)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)
        return if (goal || request.length > 600 || contextChars > 6000 || complex.containsMatchIn(request) || substantial.containsMatchIn(request.trim()) || multipleSteps) ResponseMode.DEEP else ResponseMode.QUICK
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
            "work" to listOf("work", "task", "plan", "step", "approval", "pause", "cancel", "schedule"),
            "notes" to listOf("note", "grocer", "packing list"), "reminders" to listOf("remind", "nudge"),
            "schedules" to listOf("schedule", "automatic", "every day", "weekly prompt"), "calendar" to listOf("calendar", "event"),
            "alarm" to listOf("alarm", "wake"), "share" to listOf("share"),
            "memory" to listOf("remember", "memory", "memories", "preference"), "files" to listOf("file", "document", "attachment", "pdf"),
            "skills" to listOf("skill", "workflow")
        ).filterValues { words -> words.any { terms.contains(it) } }.keys
    }
}
