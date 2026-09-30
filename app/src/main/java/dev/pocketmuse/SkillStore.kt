package dev.pocketmuse

import android.content.Context

internal class SkillStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_skills", Context.MODE_PRIVATE)
    fun read(): List<AssistantSkill> = AssistantSkills.builtIns.map { skill ->
        skill.copy(
            enabled = prefs.getBoolean("${skill.id}_enabled", true),
            instructions = prefs.getString("${skill.id}_instructions", null)
                ?.trim()?.takeIf { it.isNotBlank() }?.take(AssistantSkills.MAX_INSTRUCTIONS) ?: skill.instructions
        )
    }
    fun setEnabled(id: String, enabled: Boolean) {
        require(AssistantSkills.builtIns.any { it.id == id })
        prefs.edit().putBoolean("${id}_enabled", enabled).apply()
    }
    fun saveInstructions(id: String, instructions: String) {
        require(AssistantSkills.builtIns.any { it.id == id })
        val text = instructions.trim()
        require(text.isNotBlank() && text.length <= AssistantSkills.MAX_INSTRUCTIONS)
        prefs.edit().putString("${id}_instructions", text).apply()
    }
    fun reset(id: String) {
        require(AssistantSkills.builtIns.any { it.id == id })
        prefs.edit().remove("${id}_instructions").apply()
    }
}
