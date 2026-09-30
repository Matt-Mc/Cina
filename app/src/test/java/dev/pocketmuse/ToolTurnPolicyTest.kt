package dev.pocketmuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTurnPolicyTest {
    @Test fun repeatedWriteIsBlockedEvenAfterAnotherCall() {
        val note = "create_note:packing"
        assertEquals("Repeated tool call stopped.", ToolTurnPolicy.blockReason(note, true, listOf(note, "search_notes:packing"), 3))
    }

    @Test fun repeatedReadIsBlockedWhenConsecutive() {
        assertEquals("Repeated tool call stopped.", ToolTurnPolicy.blockReason("search_notes:packing", false, listOf("search_notes:packing"), 3))
    }

    @Test fun readCanRepeatAfterAnInterveningChange() {
        assertNull(ToolTurnPolicy.blockReason("search_notes:packing", false, listOf("search_notes:packing", "create_note:packing"), 3))
    }

    @Test fun distinctCallsAreAllowedBeforeTheBudget() {
        assertNull(ToolTurnPolicy.blockReason("create_reminder:call", true, listOf("create_note:packing"), 3))
    }

    @Test fun budgetIsCheckedBeforeAnotherAction() {
        assertEquals("Action limit reached.", ToolTurnPolicy.blockReason("fourth", true, listOf("first", "second", "third"), 3))
    }

    @Test fun newTurnDoesNotInheritOldCalls() {
        assertNull(ToolTurnPolicy.blockReason("create_note:packing", true, emptyList(), 3))
    }
}
