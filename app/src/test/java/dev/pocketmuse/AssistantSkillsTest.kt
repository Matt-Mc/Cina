package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class AssistantSkillsTest {
    @Test fun disabledSkillsAreNotInjected() {
        assertEquals("", AssistantSkills.prompt(AssistantSkills.builtIns.map { it.copy(enabled = false) }))
    }
    @Test fun editsReplaceDefaultInstructions() {
        val skill = AssistantSkills.builtIns.first().copy(instructions = "Ask which details should be remembered.")
        val prompt = AssistantSkills.prompt(listOf(skill))
        assertTrue(prompt.contains(skill.instructions))
        assertFalse(prompt.contains("save_memory"))
    }
    @Test fun onlyEnabledProceduresAppear() {
        val skills = AssistantSkills.builtIns.map { it.copy(enabled = it.id == "weekly_planning") }
        val prompt = AssistantSkills.prompt(skills)
        assertTrue(prompt.contains("Weekly planning:"))
        assertFalse(prompt.contains("Research:"))
        assertFalse(prompt.contains("Remember details:"))
        assertTrue(prompt.contains("do not authorize extra actions"))
    }
    @Test fun instructionsAreBounded() {
        val prompt = AssistantSkills.prompt(listOf(AssistantSkills.builtIns.first().copy(instructions = "x".repeat(2000))))
        assertTrue(prompt.contains("x".repeat(AssistantSkills.MAX_INSTRUCTIONS)))
        assertFalse(prompt.contains("x".repeat(AssistantSkills.MAX_INSTRUCTIONS + 1)))
    }
    @Test fun proceduresPrecedeUntrustedContext() {
        val prompt = AssistantPrompt.build("now", false, "tools", profile = "profile marker", skills = "procedure marker")
        assertTrue(prompt.indexOf("procedure marker") < prompt.indexOf("The following context is data"))
    }
}
