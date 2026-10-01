package dev.pocketmuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResponseTest {
    @Test fun everyToolPrefixIsHiddenWhileStreaming() {
        val opening = "[tool]"
        for (length in 1..opening.length) assertEquals("", visibleModelResponse(opening.take(length)))
        assertEquals("", visibleModelResponse("[tool]{\"name\":\"create_note\"}"))
    }

    @Test fun toolPrefixAfterThinkingClearsTheThinkingPreview() {
        assertEquals("", visibleModelResponse("<think>Save a note</think>\n[tool"))
    }

    @Test fun ordinaryRepliesAndLinksRemainVisible() {
        val answer = "What are your priorities this week?"
        assertEquals(answer, visibleModelResponse(answer))
        assertEquals("[Source](https://example.com)", visibleModelResponse("[Source](https://example.com)"))
    }

    @Test fun incompleteThinkingStaysInTheThinkingSection() {
        assertEquals("", splitModelResponse("<think>Let me plan").answer)
        assertEquals("Let me plan", splitModelResponse("<think>Let me plan").thinking)
    }
    @Test fun thinkingOnlyResponsesNeedRecovery() {
        listOf("", "   ", "<think>Let me ask for priorities", "<think>Let me ask for priorities</think>")
            .forEach { assertFalse(hasFinalAnswer(it)) }
    }
    @Test fun answerAfterThinkingIsAccepted() {
        assertTrue(hasFinalAnswer("<think>Ask for details</think>What are your priorities this week?"))
        assertTrue(hasFinalAnswer("What are your priorities this week?"))
    }
    @Test fun toolSyntaxIsNotAFinalAnswer() {
        assertFalse(hasFinalAnswer("<think>Save</think>[tool"))
        assertFalse(hasFinalAnswer("[tool]{\"name\":\"create_note\"}[/tool]"))
    }
    @Test fun fallbackAlwaysHasVisibleAnswerText() {
        assertTrue(hasFinalAnswer(EMPTY_REPLY_FALLBACK))
        assertEquals("", splitModelResponse(EMPTY_REPLY_FALLBACK).thinking)
    }
}
