package dev.pocketmuse

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Reads DuckDuckGo's non-JavaScript results page without an API key. */
internal object DuckDuckGoSearch {
    private val titlePattern = Regex("""<h2\b[^>]*class=["'][^"']*\bresult__title\b[^"']*["'][^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
    private val linkPattern = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val snippetPattern = Regex("""<a\b[^>]*class=["'][^"']*\bresult__snippet\b[^"']*["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    fun search(query: String): String {
        val connection = (URL("https://html.duckduckgo.com/html/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html")
        }
        try {
            val body = "q=${URLEncoder.encode(query, "UTF-8")}&b="
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode == 200) { "DuckDuckGo search failed: HTTP ${connection.responseCode}" }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                buildString {
                    val chunk = CharArray(8_192)
                    while (length < 300_000) {
                        val count = reader.read(chunk, 0, minOf(chunk.size, 300_000 - length))
                        if (count < 0) break
                        append(chunk, 0, count)
                    }
                }
            }
            val results = parse(html)
            require(results.isNotEmpty()) { "DuckDuckGo returned no readable results." }
            return results.take(5).joinToString("\n") { "${it.title} - ${it.url} - ${it.snippet}" }
        } finally {
            connection.disconnect()
        }
    }

    internal data class Result(val title: String, val url: String, val snippet: String)

    internal fun parse(html: String): List<Result> {
        val titles = titlePattern.findAll(html).toList()
        return titles.mapNotNull { titleMatch ->
            val link = linkPattern.find(titleMatch.groupValues[1]) ?: return@mapNotNull null
            val url = resolveUrl(decode(link.groupValues[1])) ?: return@mapNotNull null
            val title = clean(link.groupValues[2]).take(200)
            if (title.isBlank()) return@mapNotNull null
            val nextTitle = titles.firstOrNull { it.range.first > titleMatch.range.last }?.range?.first ?: html.length
            val snippet = snippetPattern.find(html, titleMatch.range.last + 1)
                ?.takeIf { it.range.first < nextTitle }
                ?.let { clean(it.groupValues[1]).take(500) }.orEmpty()
            Result(title, url, snippet)
        }.distinctBy { it.url }
    }

    private fun clean(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(Regex("\\s+"), " ").trim()

    private fun decode(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun resolveUrl(href: String): String? {
        val url = when {
            href.startsWith("//duckduckgo.com/l/?") || href.startsWith("https://duckduckgo.com/l/?") -> {
                val encoded = href.substringAfter("uddg=", "").substringBefore('&')
                if (encoded.isBlank()) return null
                URLDecoder.decode(encoded, "UTF-8")
            }
            href.startsWith("//") -> "https:$href"
            else -> href
        }
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}
