package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class WebLookupPolicyTest {
    @Test fun delegatedFollowUpsKeepLookupIntentWithoutInventingASubject() {
        val earlier = "What is the garbage schedule at 100 Example Avenue in Ottawa?"
        listOf("can you please check the schedule for me?", "Please look up the answer", "could you check it for me?", "please find the dates").forEach {
            assertTrue(it, WebLookupPolicy.requiresSearch(it, earlier))
            assertTrue(it, WebLookupPolicy.requiresSearch(it, "$earlier\nare you checking?"))
            assertFalse(it, WebLookupPolicy.requiresSearch(it))
            assertFalse(it, WebLookupPolicy.requiresSearch(it, "Search my notes for collection dates"))
            assertFalse(it, WebLookupPolicy.requiresSearch(it, "Don't search the web for collection dates"))
            assertFalse(it, WebLookupPolicy.requiresSearch(it, "$earlier\nDon't search the web"))
        }
    }
    @Test fun explicitLookupRequestsRequireASearch() {
        listOf("Search the web for Ottawa collection dates", "Can you please look up Ottawa collection dates?",
            "Search for stores around Ottawa that sell Riftbound", "Find information about Ottawa collection delays").forEach {
            assertTrue(it, WebLookupPolicy.requiresSearch(it))
        }
    }

    @Test fun addressSpecificCollectionQuestionRequiresASearchWithoutAMagicVerb() {
        assertTrue(WebLookupPolicy.requiresSearch("good! I'm trying to figure out the garbage schedule for my new house in Ottawa. I live at 100 Example Avenue"))
        assertTrue(WebLookupPolicy.requiresSearch("When is recycling collected at 12 Sample St N in Ottawa?"))
    }

    @Test fun checkingFollowUpKeepsTheEarlierLookupIntent() {
        assertTrue(WebLookupPolicy.requiresSearch("are you checking?", "Find out the garbage schedule at 100 Example Avenue, Ottawa"))
        assertFalse(WebLookupPolicy.requiresSearch("are you checking?", "How are you?"))
    }

    @Test fun missingLookupDetailsCanStillBeClarified() {
        listOf("Search the web", "Look up", "Search for it", "What is my garbage day?", "Hello", "What is web search?", "Find out what 2 + 2 is").forEach {
            assertFalse(it, WebLookupPolicy.requiresSearch(it))
        }
    }

    @Test fun localReadsAndRequestedWritingKeepTheirRoutingChoice() {
        listOf("Search my notes for Ottawa collection dates", "Search attached files for garbage pickup",
            "Find information in my saved notes", "Search available tools for notes", "Look up my reminders", "Search your memory for my address",
            "Write a reply saying I'll search the web", "Help me draft an explanation of the garbage schedule at 100 Example Avenue",
            "I want you to write about the garbage schedule at 100 Example Avenue",
            "Can you set a reminder to check the garbage schedule at 100 Example Avenue tomorrow at 9 AM",
            "Save the garbage schedule for 100 Example Avenue in a note").forEach {
            assertFalse(it, WebLookupPolicy.requiresSearch(it))
        }
    }

    @Test fun userCanForbidSearching() {
        assertFalse(WebLookupPolicy.requiresSearch("Don't search the web for Ottawa collection dates"))
        assertFalse(WebLookupPolicy.requiresSearch("Explain the garbage schedule at 100 Example Avenue without searching"))
    }
}
