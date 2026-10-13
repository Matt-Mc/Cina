package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class ToolResponseProtocolTest {
    private val screenshotCalls = listOf(
        """[web_search]{"name":"search","arguments":{"query":"stores around ottawa that sell riftbound"}}[/web_search]""",
        """[create_reminder]title: Bring a jacket at 11:30, when_text: "11:30 AM"[/create_reminder]"""
    )

    @Test fun screenshotCallsAreHiddenInsteadOfEndingTheTurn() {
        screenshotCalls.forEach { call ->
            assertEquals("", visibleModelResponse(call))
            assertFalse(hasFinalAnswer(call))
            assertFalse(hasFinalAnswer("<think>Use a tool</think>\n$call"))
        }
        assertEquals("web_search", ToolResponseProtocol.decode(screenshotCalls.first())!!.name)
        assertTrue(assertThrows(ToolProtocolException::class.java) { ToolResponseProtocol.decode(screenshotCalls.last()) }.repairable)
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

    @Test fun completeEnvelopesAreNormalizedAndIncompleteOnesFail() {
        val json = """{"name":"web_search","arguments":{"query":"Ottawa"}}"""
        val expected = ParsedToolCall("web_search", mapOf("query" to "Ottawa"))
        listOf(" \n[tool]$json[/tool]\n", "[TOOL]$json[/TOOL]", "<tool_call>$json</tool_call>").forEach {
            assertEquals(expected, ToolResponseProtocol.decode(it))
        }
        listOf("[tool]$json", "[tool]$json[/tool] extra", "[tool $json]", "[web_search").forEach {
            assertThrows(IllegalArgumentException::class.java) { ToolResponseProtocol.decode(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ToolResponseProtocol.decode("[tool]" + "x".repeat(32_000)) }
    }

    @Test fun ordinaryTextLinksAndInlineExamplesAreNotActions() {
        listOf("Here are the results.", "[Source](https://example.com)", "[web_search](https://example.com)",
            "The format is [tool] JSON [/tool].", "Use `[create_reminder]` as an example.",
            "[toolbox] is a label.", "<strong>Hello</strong>").forEach {
            assertEquals(it, visibleModelResponse(it))
            assertTrue(hasFinalAnswer(it))
            assertNull(ToolResponseProtocol.decode(it))
        }
        assertNull(ToolResponseProtocol.decode(""))
    }

    @Test fun newScreenshotCallsParseWithoutModelRepair() {
        assertEquals(ParsedToolCall("web_search", mapOf("query" to "stores around Ottawa that sell riftbound")),
            ToolResponseProtocol.decode("""web_search("stores around Ottawa that sell riftbound")"""))
        assertEquals(ParsedToolCall("create_reminder", mapOf("title" to "Jacket", "when_text" to "10 minutes")),
            ToolResponseProtocol.decode("""create_reminder(title: "Jacket", when_text: "10 minutes")"""))
    }

    @Test fun allToolsAcceptLiteralNamedArgumentsAndEquivalentEnvelopes() {
        ToolCatalog.definitions.forEach { tool ->
            val arguments = tool.fields.associate { field -> field.name to when (field.type) {
                "integer" -> 7L
                "object" -> mapOf("nested" to listOf("a,b(c)", true, null, 3L))
                else -> field.choices.firstOrNull() ?: "Café \"quoted\" \\ path, (details)\nnext line"
            } }
            val json = encode(arguments)
            val envelope = "{\"name\":${encode(tool.name)},\"arguments\":$json}"
            val fields = arguments.entries.joinToString(", ") { "${it.key}: ${encode(it.value)}" }
            val forms = listOf("[tool]$envelope[/tool]", "<tool_call>$envelope</tool_call>", envelope,
                "[${tool.name}]$json[/${tool.name}]", "${tool.name}($fields)", "${tool.name}($json)",
                "```json\n$envelope\n```", "`${tool.name}($fields)`")
            forms.forEach { form ->
                assertEquals(form, ParsedToolCall(tool.name, arguments), ToolResponseProtocol.decode(form))
                assertFalse(form, hasFinalAnswer(form))
                assertEquals(form, "", visibleModelResponse(form))
            }
        }
    }

    @Test fun nestedFunctionEnvelopeAndJsonArgumentStringAreUnwrapped() {
        val arguments = """{"query":"Ottawa"}"""
        val call = "{\"name\":\"web_search\",\"arguments\":${encode(arguments)}}"
        listOf("{\"type\":\"function\",\"id\":\"1\",\"function\":$call}", "[$call]").forEach {
            assertEquals(ParsedToolCall("web_search", mapOf("query" to "Ottawa")), ToolResponseProtocol.decode(it))
        }
        assertEquals(ParsedToolCall("web_search", mapOf("query" to "Ottawa's stores")),
            ToolResponseProtocol.decode("web_search(query='Ottawa\\'s stores')"))
        assertEquals(ParsedToolCall("web_search", mapOf("query" to "Café")),
            ToolResponseProtocol.decode("web_search(\"Caf\\u00e9\")"))
    }

    @Test fun functionNamePrefixesAreBufferedButOrdinaryWordsAreFinalAnswers() {
        ToolCatalog.definitions.forEach { tool ->
            val opening = "${tool.name}("
            for (length in 1..opening.length) assertEquals(opening.take(length), "", visibleModelResponse(opening.take(length)))
        }
        listOf("Save", "Read", "Sure", "web_search is an available tool.").forEach { assertTrue(it, hasFinalAnswer(it)) }
        val json = """{"name":"web_search","arguments":{"query":"Ottawa"}}"""
        for (length in 1..json.length) {
            assertEquals(json.take(length), "", visibleModelResponse(json.take(length)))
            assertFalse(json.take(length), hasFinalAnswer(json.take(length)))
        }
        assertTrue(hasFinalAnswer("""{"name":"Ada"}"""))
    }

    @Test fun ambiguousOrExecutableContentNeverGetsRepairedOrDispatched() {
        val envelope = """{"name":"web_search","arguments":{"query":"Ottawa"}}"""
        listOf(
            "web_search(query=System.exit(0))", "web_search(query='a' + 'b')", "web_search('a', 'b')",
            "create_note('Title', 'Body')", "web_search(query='a', query='b')",
            "web_search(\"unfinished)", "web_search('a'); create_note(title='x',body='y')",
            "unknown_tool(query='x')", "web_search(query=NaN)", "web_search(query=1e999)",
            "[tool]$envelope[/tool][tool]$envelope[/tool]", "[$envelope,$envelope]",
            "[create_note]title: A[/create_note]", "[create_note]title: A, body: B, unknown: C[/create_note]",
            "[create_reminder]title: Bring a jacket[/create_reminder]",
            "[delete_note]id: one, version: two[/delete_note]",
            """[create_note]{"name":"delete_note","arguments":{"id":1}}[/create_note]""",
            """{"name":"web_search","name":"delete_note","arguments":{}}""",
            """{"name":"web_search","arguments":{"query":"x","query":"y"}}""",
            """{"name":"web_search","arguments":{"query":"x"},"extra":true}""",
            "```python\nweb_search('Ottawa')", "web_search('a') trailing text",
            "mcp_call(server='a',tool='b',arguments=" + "[".repeat(15) + "0" + "]".repeat(15) + ")"
        ).forEach { source ->
            assertFalse(source, hasFinalAnswer(source))
            val error = assertThrows(source, ToolProtocolException::class.java) { ToolResponseProtocol.decode(source) }
            assertFalse(source, error.repairable)
        }
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { encode(it.key) + ":" + encode(it.value) }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::encode)
        else -> value.toString()
    }
}
