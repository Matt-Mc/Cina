package dev.pocketmuse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class SkillStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_skills", Context.MODE_PRIVATE)
    private fun customSkills(): List<AssistantSkill> {
        val array = JSONArray(prefs.getString("custom_skills", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            AssistantSkill(item.getString("id"), item.getString("title"), item.getString("description"),
                item.getString("instructions"), custom = true)
        }
    }
    @Synchronized fun read(): List<AssistantSkill> = (AssistantSkills.builtIns + customSkills()).map { skill ->
        skill.copy(
            enabled = prefs.getBoolean("${skill.id}_enabled", true),
            instructions = prefs.getString("${skill.id}_instructions", null)
                ?.trim()?.takeIf { it.isNotBlank() }?.take(AssistantSkills.MAX_INSTRUCTIONS) ?: skill.instructions
        )
    }
    private fun persistCustom(skills: List<AssistantSkill>) {
        val array = JSONArray()
        skills.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("description", it.description).put("instructions", it.instructions)) }
        check(prefs.edit().putString("custom_skills", array.toString()).commit()) { "Skill could not be saved." }
    }
    @Synchronized fun create(title: String, description: String, instructions: String): AssistantSkill {
        val name = title.trim(); val detail = description.trim(); val text = instructions.trim()
        require(name.length in 1..60) { "Skill title must be 1 to 60 characters." }
        require(detail.length in 1..180) { "Description must be 1 to 180 characters." }
        require(text.length in 1..AssistantSkills.MAX_INSTRUCTIONS) { "Instructions must be 1 to 1200 characters." }
        val all = read()
        val existing = all.firstOrNull { it.title.equals(name, true) }
        if (existing != null) {
            require(existing.custom && existing.description == detail && existing.instructions == text) { "A skill with this title already exists. Edit it in Settings > Skills." }
            return existing
        }
        val custom = customSkills()
        require(custom.size < AssistantSkills.MAX_CUSTOM_SKILLS) { "Custom skill limit reached. Remove an unused skill in Settings > Skills." }
        val skill = AssistantSkill("custom_" + UUID.randomUUID().toString(), name, detail, text, custom = true)
        persistCustom(custom + skill)
        return skill
    }
    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        require(read().any { it.id == id })
        prefs.edit().putBoolean("${id}_enabled", enabled).apply()
    }
    @Synchronized fun saveInstructions(id: String, instructions: String) {
        require(read().any { it.id == id })
        val text = instructions.trim()
        require(text.isNotBlank() && text.length <= AssistantSkills.MAX_INSTRUCTIONS)
        prefs.edit().putString("${id}_instructions", text).apply()
    }
    @Synchronized fun reset(id: String) {
        require(AssistantSkills.builtIns.any { it.id == id })
        prefs.edit().remove("${id}_instructions").apply()
    }
    @Synchronized fun delete(id: String) {
        val custom = customSkills()
        require(custom.any { it.id == id })
        persistCustom(custom.filterNot { it.id == id })
        prefs.edit().remove("${id}_enabled").remove("${id}_instructions").apply()
    }
}
