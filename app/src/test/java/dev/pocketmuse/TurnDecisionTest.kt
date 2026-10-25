package dev.pocketmuse

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnDecisionTest {
    @Test fun routingKeepsTheCapabilityFilterAndAddsOnlyANonExecutableReplyChoice() {
        val schema = JSONObject(TurnDecision.schema(ToolCatalog.schema(onlyNames = setOf("web_search"))))
        val choices = schema.getJSONArray("oneOf")
        assertEquals(setOf("web_search", TurnDecision.REPLY), (0 until choices.length()).map {
            choices.getJSONObject(it).getJSONObject("properties").getJSONObject("name").getString("const")
        }.toSet())
        assertNull(ToolCatalog.get(TurnDecision.REPLY))
        assertTrue(runCatching { ToolResponseProtocol.decode(TurnDecision.REPLY_CALL) }.isFailure)
    }

    @Test fun replyChoiceMustBeExactAndCannotHideAnActionOrExtraText() {
        assertTrue(TurnDecision.isReply(TurnDecision.REPLY_CALL))
        assertTrue(TurnDecision.isReply("<think>Enough information</think>" + TurnDecision.REPLY_CALL))
        listOf("I'll search now.", TurnDecision.REPLY_CALL + " I'll search now.",
            TurnDecision.REPLY_CALL + TurnDecision.REPLY_CALL,
            "[tool]{\"name\":\"reply_now\",\"arguments\":{\"query\":\"Ottawa\"}}[/tool]",
            "[tool]{\"name\":\"web_search\",\"arguments\":{\"query\":\"Ottawa\"}}[/tool]").forEach {
            assertFalse(it, TurnDecision.isReply(it))
        }
    }

    @Test fun screenshotPromisesAreNotCompletedWork() {
        listOf("I can help with that. Let me check the current garbage schedule in Ottawa.",
            "I'm not searching for it, but I can help with that. Let me check the current garbage schedule in Ottawa.",
            "I am checking the current garbage schedule in Ottawa. Let me know if you need further assistance!",
            "I'll look up the collection schedule.", "I’m searching the web now.",
            "I'm working on your request.").forEach {
            assertTrue(it, TurnDecision.promisesWork(it, "Find my collection schedule"))
        }
    }

    @Test fun answersClarificationsLimitationsAndRequestedDraftsAreAllowed() {
        listOf("Which city and street should I use?", "I couldn't verify your pickup day from the search results.",
            "The city lookup requires you to enter your address: https://example.com/collection",
            "Your pickup day is Tuesday according to the returned city schedule.",
            "I checked the returned results; the address lookup needs another detail.",
            "The phrase 'let me check' does not start a search.").forEach {
            assertFalse(it, TurnDecision.promisesWork(it, "Find my collection schedule"))
        }
        assertFalse(TurnDecision.promisesWork("I'll check the schedule and get back to you.", "Write a reply to Sam"))
    }
}
