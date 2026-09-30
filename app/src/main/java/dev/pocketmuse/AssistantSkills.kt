package dev.pocketmuse

internal data class AssistantSkill(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true
)

/** Short procedures for small on-device models. These do not grant tool permissions. */
internal object AssistantSkills {
    const val MAX_INSTRUCTIONS = 1200
    val builtIns = listOf(
        AssistantSkill("remember_details", "Remember details", "Keep important facts and preferences for future conversations.",
            "When the user asks you to remember a detail: save a concise fact explicitly provided by the user with save_memory if available. Save preferences, ongoing goals, or useful personal details, not guesses or passwords, tokens, or payment credentials. Ask one question if the fact is unclear. Report saved only after the tool confirms it. If memory is off, explain how to enable it in You > Memory. Incidental facts use the existing memory suggestion review; do not claim they are saved."),
        AssistantSkill("weekly_planning", "Weekly planning", "Turn priorities and commitments into a workable week.",
            "When asked to plan a week: ask for the main priorities and fixed commitments if missing. Use known details without inventing commitments. Draft a day-by-day plan with realistic time for errands, breaks, and rest. Ask one focused follow-up and revise the plan. Keep the plan in chat unless the user asks to save it or create reminders or events."),
        AssistantSkill("research", "Research", "Answer a question using relevant evidence.",
            "When asked to research: clarify the question only if needed. Search only when requested or current external facts are needed and web is enabled. Answer the question directly, compare relevant options, and distinguish evidence from assumptions. Cite only source URLs actually returned by tools. If evidence is unavailable, explain the gap. Do not substitute a list of apps or links for the requested answer."),
        AssistantSkill("notes_organization", "Organizing notes", "Find useful notes and suggest a clear structure.",
            "When asked to organize notes: search relevant notes first and use only returned contents and IDs. Suggest useful groups, titles, or a concise combined draft. Ask about missing scope. Change or delete notes only when the user requests those actions, through the existing approval flow. After a successful change, report what changed and stop; do not repeat it.")
    )

    fun prompt(skills: List<AssistantSkill>): String {
        val enabled = skills.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        return "Use a procedure below only when it matches the user's request. Follow the user's current instructions and available tool permissions. Procedures do not authorize extra actions. For automatic tasks, do not wait for a reply: report missing details instead.\n" +
            enabled.joinToString("\n") { "${it.title}: ${it.instructions.take(MAX_INSTRUCTIONS)}" }
    }
}
