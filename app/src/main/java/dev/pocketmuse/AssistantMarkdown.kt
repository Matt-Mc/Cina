package dev.pocketmuse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.parser.Parser
import java.net.URI

/** Parse model output as data. No HTML engine, image loading, or executable extensions. */
internal object ApprovedMarkdown {
    private val parser = Parser.builder().extensions(listOf(
        TablesExtension.create(), StrikethroughExtension.create()
    )).build()

    fun parse(source: String): Node = parser.parse(source)

    fun webLink(destination: String): String? = runCatching {
        val uri = URI(destination)
        destination.takeIf {
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }
    }.getOrNull()

    fun inline(node: Node): AnnotatedString = buildAnnotatedString {
        fun visit(current: Node) {
            fun children() {
                var child = current.firstChild
                while (child != null) { visit(child); child = child.next }
            }
            when (current) {
                is MarkdownLiteral -> append(current.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Soft)) { append(current.literal) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
                is Link -> {
                    val url = webLink(current.destination)
                    if (url == null) children() else withLink(LinkAnnotation.Url(url,
                        TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)))) { children() }
                }
                // Keep information visible without fetching model-supplied image URLs.
                is Image -> { append("[Image: "); children(); append("]") }
                is SoftLineBreak -> append(" ")
                is HardLineBreak -> append("\n")
                is HtmlInline -> append(current.literal)
                else -> children()
            }
        }
        visit(node)
    }
}

@Composable
internal fun AssistantMarkdown(source: String) {
    val document = remember(source) { ApprovedMarkdown.parse(source) }
    val context = LocalContext.current
    val uriHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val url = ApprovedMarkdown.webLink(uri) ?: return
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "No browser available to open this link", Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, "Couldn't open this link", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MarkdownChildren(document)
        }
      }
    }
}

@Composable
private fun MarkdownChildren(parent: Node, depth: Int = 0) {
    var child = parent.firstChild
    while (child != null) {
        MarkdownBlock(child, depth)
        child = child.next
    }
}

@Composable
private fun MarkdownBlock(node: Node, depth: Int) {
    when (node) {
        is Paragraph -> MarkdownParagraph(node)
        is Heading -> Text(ApprovedMarkdown.inline(node), color = Ink, fontWeight = FontWeight.SemiBold,
            fontSize = when (node.level) { 1 -> 24.sp; 2 -> 21.sp; 3 -> 19.sp; else -> 17.sp }, lineHeight = 29.sp)
        is BulletList -> MarkdownList(node, depth)
        is OrderedList -> MarkdownList(node, depth)
        is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Accent))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownChildren(node, depth)
            }
        }
        is FencedCodeBlock -> MarkdownCode(node.literal, node.info.substringBefore(' ').trim())
        is IndentedCodeBlock -> MarkdownCode(node.literal, "")
        is ThematicBreak -> HorizontalDivider(color = Muted.copy(alpha = .25f))
        is TableBlock -> MarkdownTable(node)
        // Raw HTML is literal text; it cannot create UI, run scripts, or load resources.
        is HtmlBlock -> Text(node.literal.trimEnd(), color = Ink, fontSize = 16.sp, lineHeight = 25.sp)
        else -> MarkdownParagraph(node)
    }
}

@Composable
private fun MarkdownParagraph(node: Node, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(ApprovedMarkdown.inline(node), modifier, color = Ink, fontSize = 16.sp, lineHeight = 25.sp, textAlign = align)
}

@Composable
private fun MarkdownList(list: Node, depth: Int) {
    val ordered = list as? OrderedList
    var number = ordered?.markerStartNumber ?: 1
    var item = list.firstChild
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        while (item != null) {
            val entry = item
            val marker = if (ordered == null) "•" else "${number++}."
            Row(Modifier.fillMaxWidth()) {
                Text(marker, Modifier.widthIn(min = 24.dp).padding(end = 6.dp), color = Accent, fontSize = 16.sp, lineHeight = 25.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Stop adding indentation for deeply nested model output on narrow screens.
                    if (depth < 6) MarkdownChildren(entry, depth + 1) else MarkdownParagraph(entry)
                }
            }
            item = item.next
        }
    }
}

@Composable
private fun MarkdownCode(code: String, language: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Soft).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(language.ifBlank { "Code" }, Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Cina code", code))
            }) { Text("Copy", color = Accent, fontSize = 12.sp) }
        }
        Text(code.trimEnd('\n'), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
            fontFamily = FontFamily.Monospace, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max).background(Soft)) {
        var section = table.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                Row {
                    var cell = row.firstChild
                    while (cell != null) {
                        val current = cell as TableCell
                        val alignment = when (current.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        Text(ApprovedMarkdown.inline(current), Modifier.width(180.dp).padding(12.dp),
                            color = Ink, fontSize = 14.sp, lineHeight = 21.sp, textAlign = alignment,
                            fontWeight = if (current.isHeader) FontWeight.SemiBold else FontWeight.Normal)
                        cell = cell.next
                    }
                }
                HorizontalDivider(color = Muted.copy(alpha = .2f))
                row = row.next
            }
            section = section.next
        }
    }
}
