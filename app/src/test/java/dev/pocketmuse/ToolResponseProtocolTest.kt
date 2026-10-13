package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class ToolResponseProtocolTest {
    private val screenshotCalls = listOf(
        """[web_search]{"name":"search","arguments":{"query":"stores around ottawa that sell riftbound"}}[/web_search]""",
        """[create_reminder]title: Bring a jacket at 11:30, when_text: "11:30 AM"[/create_reminder]"""
    )

    @Test fun screenshotCallsAreHiddenAndRequireRepairInsteadOfEndingTheTurn() {
        screenshotCalls.forEach { call ->
            assertEquals("", visibleModelResponse(call))
            assertFalse(hasFinalAnswer(call))
            assertThrows(IllegalArgumentException::class.java) { ToolResponseProtocol.payload(call) }
            assertFalse(hasFinalAnswer("<think>Use a tool</think>\n$call"))
        }
    }

    @Test fun alternateAndPartialOpeningsNeverLeakDuringStreaming() {
        val names = listOf("tool", "tool_call") + ToolCatalog.definitions.map { it.name }
        names.flatMap { listOf("[$it]", "<$it>") }.forEach { opening ->
            for (length in 1..opening.length) {
                assertEquals(opening.take(length), "", visibleModelResponse(opening.take(length)))
            }
            assertEquals("", visibleModelResponse("<think>Act</think>\n$opening{}"))
        }
    }

    @Test fun onlyCanonicalCompleteEnvelopeCanBeParsed() {
        val json = """{"name":"web_search","arguments":{"query":"Ottawa"}}"""
        assertEquals(json, ToolResponseProtocol.payload(" \n[tool]$json[/tool]\n"))
        listOf("[tool]$json", "[tool]$json[/tool] extra", "[TOOL]$json[/TOOL]",
            "<tool_call>$json</tool_call>", "[tool $json]", "[web_search").forEach {
            assertThrows(IllegalArgumentException::class.java) { ToolResponseProtocol.payload(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ToolResponseProtocol.payload("[tool]" + "x".repeat(32_000)) }
    }

    @Test fun ordinaryTextLinksAndInlineExamplesAreNotActions() {
        listOf("Here are the results.", "[Source](https://example.com)", "[web_search](https://example.com)",
            "The format is [tool] JSON [/tool].", "Use `[create_reminder]` as an example.",
            "[toolbox] is a label.", "<strong>Hello</strong>").forEach {
            assertEquals(it, visibleModelResponse(it))
            assertTrue(hasFinalAnswer(it))
            assertNull(ToolResponseProtocol.payload(it))
        }
        assertNull(ToolResponseProtocol.payload(""))
    }
}
