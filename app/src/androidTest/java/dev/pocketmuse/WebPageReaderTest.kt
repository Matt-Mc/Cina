package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class WebPageReaderTest {
    @Test fun pageTextPreservesFactsAndResolvesLinksButDoesNotExposeScripts() {
        val result = WebPageReader.extract("https://example.com/waste/", """
            <html><style>hidden-style</style><script>hidden-script</script>
            <h1>Collection schedule</h1><p>100 Example Avenue: Tuesday &amp; Friday.</p>
            <a href="../calendar?year=2026&amp;month=10">Calendar</a>
            <a href="javascript:alert(1)">Bad link</a></html>
        """.trimIndent())
        assertTrue(result.contains("100 Example Avenue: Tuesday & Friday."))
        assertTrue(result.contains("https://example.com/calendar?year=2026&month=10"))
        assertFalse(result.contains("hidden-script"))
        assertFalse(result.contains("hidden-style"))
        assertFalse(result.contains("javascript:"))
    }

    @Test fun emptyDynamicPagesAndTruncationAreExplicit() {
        val empty = WebPageReader.extract("https://example.com", "<script>loadSchedule()</script>")
        assertTrue(empty.contains("No readable text"))
        assertTrue(empty.contains("no JavaScript, login or form submission"))
        val long = WebPageReader.extract("https://example.com", "a".repeat(9000), html = false)
        assertTrue(long.contains("Page shortened"))
        assertTrue(long.length < 7000)
    }
}
