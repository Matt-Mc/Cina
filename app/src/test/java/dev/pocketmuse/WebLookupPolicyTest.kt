package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class WebLookupPolicyTest {
    @Test fun simpleLookupsFinishWithTextWhileResearchAndRemainingActionsKeepTools() {
        assertTrue(WebLookupPolicy.lookupOnly("Search the web for Ottawa collection dates"))
        assertTrue(WebLookupPolicy.lookupOnly("I'm trying to figure out the garbage schedule at 100 Example Avenue in Ottawa"))
        assertFalse(WebLookupPolicy.lookupOnly("Search the web for Ottawa collection dates and save a note"))
        assertFalse(WebLookupPolicy.lookupOnly("Search the web and compare these collection policies in detail"))
        assertFalse(WebLookupPolicy.lookupOnly("Look up Ottawa collection dates; remind me tomorrow at 9 AM"))
        assertTrue(WebLookupPolicy.lookupOnly("are you checking?", "What is the garbage schedule at 100 Example Avenue in Ottawa?"))
        assertFalse(WebLookupPolicy.lookupOnly("Search the web for Ottawa collection dates; schedule it for tomorrow"))
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
