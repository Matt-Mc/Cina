package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class ContextSearchPolicyTest {
    @Test fun treatsOperatorsAsWordsAndKeepsUnicode() {
        assertEquals("\"tea\" AND \"OR\" AND \"coffee\"",ContextSearchPolicy.match("tea OR coffee"))
        assertEquals("\"café\" AND \"茶\"",ContextSearchPolicy.match("café 茶"))
        assertEquals("\"body\" AND \"secret\"",ContextSearchPolicy.match("body:secret*"))
        assertNull(ContextSearchPolicy.match("*** \" ;"))
    }
    @Test fun boundsQueryWork() {
        assertTrue(runCatching { ContextSearchPolicy.match("x".repeat(301)) }.isFailure)
        assertTrue(runCatching { ContextSearchPolicy.match("one two three four five six seven eight nine") }.isFailure)
        assertEquals("\"tea\"",ContextSearchPolicy.match("tea tea tea"))
    }
}
