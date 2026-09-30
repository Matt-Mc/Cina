package dev.pocketmuse

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class ApprovedMarkdownTest {
    @Test fun nestedFormattingAndListsUseParsedStructure() {
        val document = ApprovedMarkdown.parse("## A plan\n\n1. **First** and *gentle*\n   - `one step`\n2. Next")
        assertTrue(document.firstChild is Heading)
        val list = document.firstChild.next as OrderedList
        assertEquals(1, list.markerStartNumber)
        val paragraph = list.firstChild.firstChild
        val text = ApprovedMarkdown.inline(paragraph)
        assertEquals("First and gentle", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.next is BulletList)
    }

    @Test fun onlyApprovedWebLinksAreInteractive() {
        val paragraph = ApprovedMarkdown.parse(
            "[web](https://example.com/page) [script](javascript:alert) [file](file:///sdcard/private) [app](intent://open)"
        ).firstChild
        val text = ApprovedMarkdown.inline(paragraph)
        assertEquals("web script file app", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/page", (links.single().item as LinkAnnotation.Url).url)
        listOf("https://", "https://user:secret@example.com", "data:text/html,hi", "//example.com").forEach {
            assertNull(ApprovedMarkdown.webLink(it))
        }
    }

    @Test fun htmlAndImagesRemainInertText() {
        val paragraph = ApprovedMarkdown.parse("hello <b>world</b> ![diagram](https://example.com/image.png)").firstChild
        val text = ApprovedMarkdown.inline(paragraph)
        assertEquals("hello <b>world</b> [Image: diagram]", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertTrue(ApprovedMarkdown.parse("<script>alert('x')</script>").firstChild is HtmlBlock)
    }

    @Test fun streamingCanStopInTheMiddleOfFormattingOrCode() {
        assertEquals("Keep **going", ApprovedMarkdown.inline(ApprovedMarkdown.parse("Keep **going").firstChild).text)
        val code = ApprovedMarkdown.parse("```kotlin\nval answer = 42").firstChild as FencedCodeBlock
        assertEquals("kotlin", code.info)
        assertEquals("val answer = 42\n", code.literal)
    }

    @Test fun tablesAndStrikethroughAreSupported() {
        assertTrue(ApprovedMarkdown.parse("| Item | Time |\n| --- | --- |\n| Walk | 10 min |").firstChild is TableBlock)
        assertEquals("old new", ApprovedMarkdown.inline(ApprovedMarkdown.parse("~~old~~ new").firstChild).text)
    }
}
