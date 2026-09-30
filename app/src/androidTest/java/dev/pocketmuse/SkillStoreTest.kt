package dev.pocketmuse

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SkillStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "skill_store_test_" + UUID.randomUUID()
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(preferenceName, mode)
    }
    private val store = SkillStore(context)
    @After fun cleanup() { base.deleteSharedPreferences(preferenceName) }

    @Test fun customSkillPersistsEditsAndDisabledState() {
        val skill = store.create("Meal planning", "When planning meals", "Ask for dietary preferences, then draft meals and check them.")
        store.saveInstructions(skill.id, "Use vegetarian meals.")
        store.setEnabled(skill.id, false)
        val restored = SkillStore(context).read().first { it.id == skill.id }
        assertTrue(restored.custom)
        assertFalse(restored.enabled)
        assertEquals("Use vegetarian meals.", restored.instructions)
    }
    @Test fun duplicateCreationDoesNotAddAnotherSkill() {
        val first = store.create("Meals", "Plan meals", "Ask for preferences.")
        val second = store.create("meals", "Plan meals", "Ask for preferences.")
        assertEquals(first.id, second.id)
        assertEquals(1, store.read().count { it.custom })
    }
    @Test fun conflictingTitlesCannotOverwriteSkills() {
        store.create("Meals", "Plan meals", "Ask for preferences.")
        rejects { store.create("Meals", "Different workflow", "Replace everything.") }
        rejects { store.create(AssistantSkills.builtIns.first().title, "Override", "Replace instructions.") }
        assertEquals("Ask for preferences.", store.read().first { it.custom }.instructions)
    }
    @Test fun limitsRejectInvalidCreations() {
        rejects { store.create("", "Description", "Instructions") }
        rejects { store.create("x".repeat(61), "Description", "Instructions") }
        rejects { store.create("Meals", "x".repeat(181), "Instructions") }
        rejects { store.create("Meals", "Description", "x".repeat(1201)) }
        repeat(AssistantSkills.MAX_CUSTOM_SKILLS) { store.create("Skill $it", "Description", "Instructions") }
        rejects { store.create("Overflow", "Description", "Instructions") }
    }
    @Test fun deletionRemovesOnlyTheSelectedCustomSkill() {
        val skill = store.create("Meals", "Plan meals", "Ask for preferences.")
        rejects { store.delete(AssistantSkills.builtIns.first().id) }
        store.delete(skill.id)
        assertFalse(SkillStore(context).read().any { it.id == skill.id })
        assertEquals(AssistantSkills.builtIns.size, store.read().size)
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
