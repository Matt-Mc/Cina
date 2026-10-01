package dev.pocketmuse

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {
    private fun message(id: Long, role: String, body: String) = Message(id, 1, role, body, 0)
    private suspend fun count(text: String) = text.codePointCount(0, text.length)

    @Test fun retainsDetailsBeyondOldCharacterLimit() = runBlocking {
        val body = "a".repeat(500) + " Work ends at six."
        val result = ConversationContext.recent(listOf(message(1, "user", body)), 1000, ::count)
        assertEquals("user: $body", result)
    }

    @Test fun keepsWholeNewestMessagesInOrderWithinSharedBudget() = runBlocking {
        val result = ConversationContext.recent(listOf(
            message(1, "user", "old".repeat(30)),
            message(2, "assistant", "<think>private reasoning</think>What days?"),
            message(3, "user", "Tuesday and Thursday")
        ), 60, ::count)
        assertEquals("assistant: What days?\nuser: Tuesday and Thursday", result)
        assertFalse(result.contains("private reasoning"))
        assertTrue(count(result) <= 60)
    }

    @Test fun canKeepMoreThanEightShortMessages() = runBlocking {
        val result = ConversationContext.recent((1L..12L).map { message(it, "user", "item $it") }, 500, ::count)
        assertEquals(12, result.lines().size)
    }

    @Test fun oversizedNewestMessageKeepsBothEndsWithoutBreakingUnicode() = runBlocking {
        val result = ConversationContext.recent(listOf(message(1, "user", "START" + "😀".repeat(200) + "END")), 70, ::count)
        assertTrue(result.startsWith("user: START"))
        assertTrue(result.endsWith("END"))
        assertTrue(result.contains("[message shortened]"))
        assertTrue(count(result) <= 70)
    }

    @Test fun zeroBudgetIsEmpty() = runBlocking {
        assertEquals("", ConversationContext.recent(listOf(message(1, "user", "hello")), 0, ::count))
    }

    @Test fun recoverySnapshotIncludesAllContextSources() {
        val context = ConversationContext.snapshot("Matt", "Likes evening runs", "Moving soon", "user: Thursday is busy")
        listOf("Matt", "Likes evening runs", "Moving soon", "user: Thursday is busy").forEach {
            assertTrue(context.contains(it))
        }
    }
}
