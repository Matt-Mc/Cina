package dev.pocketmuse

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Audit observations only; these assertions document current behavior, not desired behavior. */
class AuditPolicyProbeTest {
    @Test fun scheduledCatalogLosesFiveAllowedToolsWhenChatHasNoUserMessage() {
        val source = File("src/main/java/dev/pocketmuse/AssistantTools.kt").readText()
        val scheduled = source.substringAfter("if (scheduledModelPath != null) \"\"\"")
            .substringBefore("\"\"\".trimIndent()") .trimIndent()
        val result = ResponsePolicy.tools(scheduled, "")
        assertTrue(result.contains("create_note("))
        listOf("search_notes", "create_reminder", "search_reminders", "create_scheduled_task", "list_scheduled_tasks").forEach {
            assertTrue("Source should contain $it", scheduled.contains("$it("))
            assertFalse("Observed hidden tool: $it", result.contains("$it("))
        }
        println("SCHEDULED CATALOG AFTER ROUTING:\n$result")
    }

    @Test fun followUpAndPdfRequestsDoNotSelectRelevantToolGroups() {
        val spec = "local_find(query:string)\ncreate_reminder(title:string, when_iso:string)\nreschedule_reminder(id:int,title:string,when_iso:string)\nsearch_attachments(query:string)"
        val followUp = ResponsePolicy.tools(spec, "Move it to tomorrow at 3 pm")
        val pdf = ResponsePolicy.tools(spec, "Summarize this PDF")
        assertFalse(followUp.contains("reschedule_reminder("))
        assertFalse(pdf.contains("search_attachments("))
        assertTrue(followUp.contains("local_find("))
        println("FOLLOW-UP TOOLS: $followUp\nPDF TOOLS: $pdf")
    }

    @Test fun discoveryConsumesTheQuickActionBudget() {
        val discovery = listOf("find_skill:organize", "local_find:notes", "search_notes:trip")
        assertEquals("Action limit reached.", ToolTurnPolicy.blockReason("edit_note:1", true, discovery, 3))
    }
}
