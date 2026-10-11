package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class ResponsePolicyTest {
    @Test fun autoKeepsOrdinaryQuestionsQuickAndRoutesComplexWorkDeep() {
        assertEquals(ResponseMode.QUICK, ResponsePolicy.resolve(ResponseMode.AUTO, "I'm traveling to Lisbon next week, what should I pack?"))
        assertEquals(ResponseMode.DEEP, ResponsePolicy.resolve(ResponseMode.AUTO, "Compare the tradeoffs of these itineraries"))
        assertEquals(ResponseMode.DEEP, ResponsePolicy.resolve(ResponseMode.AUTO, "Organize my trip", goal = true))
    }
    @Test fun manualChoiceOverridesRouting() {
        assertEquals(ResponseMode.QUICK, ResponsePolicy.resolve(ResponseMode.QUICK, "Analyze in detail", goal = true))
        assertEquals(ResponseMode.DEEP, ResponsePolicy.resolve(ResponseMode.DEEP, "Hello"))
    }
    @Test fun simpleChatDoesNotLoadEveryProcedureButKeepsDiscovery() {
        val prompt = ResponsePolicy.skills(AssistantSkills.builtIns, "Hello")
        assertTrue(prompt.contains("find_skill"))
        assertFalse(prompt.contains("Call create_skill once"))
        assertFalse(prompt.contains("Draft a day-by-day plan"))
        assertTrue(ResponsePolicy.skills(AssistantSkills.builtIns, "weekly planning").contains("Draft a day-by-day plan"))
    }
    @Test fun toolSelectionHasAnEscapeRoute() {
        val spec = "local_find(query:string)\ncreate_note(title:string)\ncreate_reminder(title:string)\nweb_search(query:string)"
        val simple = ResponsePolicy.tools(spec, "Hello")
        assertFalse(simple.contains("create_note("))
        assertTrue(simple.contains("local_find("))
        assertTrue(ResponsePolicy.tools(spec, "remind me tomorrow").contains("create_reminder("))
    }
    @Test fun credentialsAreExcludedAndSensitiveDetailsNeedReview() {
        assertTrue(MemorySafety.isCredential("My API key is abc"))
        assertTrue(MemorySafety.needsReview("I was diagnosed with diabetes"))
        assertFalse(MemorySafety.needsReview("I am visiting Lisbon next week"))
        assertTrue(MemorySafety.optOut("Don't remember this trip"))
    }
    @Test fun relativePlansUseTheSourceDateRatherThanProcessingDate() {
        assertEquals(java.time.LocalDate.of(2026, 10, 19), MemorySafety.relativeCutoff(java.time.LocalDate.of(2026, 10, 11), "Lisbon next week"))
        assertEquals(java.time.LocalDate.of(2026, 10, 20), MemorySafety.relativeCutoff(java.time.LocalDate.of(2026, 10, 18), "Appointment tomorrow"))
    }
}
