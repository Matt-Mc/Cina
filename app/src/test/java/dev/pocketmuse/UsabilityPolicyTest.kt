package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class UsabilityPolicyTest {
    @Test fun scheduledCatalogContainsEveryAllowedDefinitionRegardlessOfWording() {
        val allowed = ToolCatalog.definitions.filter { it.scheduled }.map { it.name }.toSet()
        assertEquals(allowed, ToolCatalog.select("Hello", scheduled = true).map { it.name }.toSet())
        val rendered = ToolCatalog.render(ToolCatalog.select("Hello", scheduled = true), true)
        allowed.forEach { assertTrue(rendered.contains("$it — arguments:")) }
        assertFalse(rendered.contains("mcp_call — arguments:"))
    }
    @Test fun shortFollowUpsKeepRecentActionsAndFilesAreAvailableWithoutKeywordGuessing() {
        assertTrue(ToolCatalog.select("Move it to tomorrow at 3 pm", recent = "Remind me to call Sam").any { it.name == "reschedule_reminder" })
        assertTrue(ToolCatalog.select("What does it say?", hasFiles = true).any { it.name == "search_attachments" })
        assertTrue(ToolCatalog.select("Read this PDF").any { it.name == "search_attachments" })
        assertFalse(ToolCatalog.select("Search the web", web = false).any { it.name == "web_search" })
    }
    @Test fun discoveryAndReadsDoNotConsumeTheWriteBudget() {
        val steps = listOf("local_find:notes", "search_notes:packing", "read_note:4", "create_note:a", "create_note:b")
        assertNull(ToolTurnPolicy.blockReason("create_note:c", true, steps, 3))
        assertEquals("Action limit reached.", ToolTurnPolicy.blockReason("create_note:d", true, steps + "create_note:c", 3))
        assertEquals("Action limit reached.", ToolTurnPolicy.blockReason("local_find:new", false, List(4) { "local_find:$it" }, 3))
    }
    private val now = ZonedDateTime.of(2026, 10, 13, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
    @Test fun appResolvesTimeWithoutAskingTheModelForTimezoneMath() {
        assertEquals(Instant.parse("2026-10-14T19:00:00Z"), ReminderTime.parse("tomorrow at 3 pm", now))
        assertEquals(Instant.parse("2026-10-13T14:00:00Z"), ReminderTime.parse("in 1 hour", now))
        assertEquals(Instant.parse("2026-10-13T16:00:00Z"), ReminderTime.parse("today at noon", now))
    }
    @Test fun ambiguousPastAndDaylightSavingTimesRequireUserInput() {
        listOf("tomorrow at 3", "someday", "today at 8 am", "2026-11-01 at 1:30 am", "2027-03-14 at 2:30 am").forEach {
            assertTrue("Should reject $it", runCatching { ReminderTime.parse(it, now) }.isFailure)
        }
    }
}
