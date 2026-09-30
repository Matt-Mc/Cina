package dev.pocketmuse

import org.junit.Assert.assertEquals
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
}
