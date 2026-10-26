package dev.pocketmuse

import android.text.Html
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/** Bounded, unauthenticated public-page reads. Does not execute scripts or submit forms. */
internal object WebPageReader {
    private const val MAX_BYTES = 300_000
    private const val MAX_TEXT = 6_000
    private val hidden = Regex("""<(script|style|noscript|svg)\b[^>]*>.*?</\1\s*>|<!--[\s\S]*?-->""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val links = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    internal fun publicUrl(raw: String): URL {
        val uri = URI(raw)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            (uri.port == -1 || uri.port == if (uri.scheme.equals("https", true)) 443 else 80)) {
            "Use a public HTTP or HTTPS page URL without credentials or a custom port."
        }
        val host = uri.host.lowercase().trimEnd('.')
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Local pages are unavailable to web_read." }
        return uri.toURL()
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return if (bytes.size == 4) bytes[0] != 0 && bytes[0] < 224 &&
            !(bytes[0] == 100 && bytes[1] in 64..127) else (bytes[0] and 0xfe) != 0xfc
    }

    fun read(raw: String): String {
        var url = publicUrl(raw)
        repeat(4) { hop ->
            require(InetAddress.getAllByName(url.host).all(::isPublicAddress)) { "Only public internet pages can be read." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "text/html, text/plain, application/json")
                setRequestProperty("User-Agent", "Cina/1.0 (public page reader)")
            }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    require(hop < 3) { "This page redirects too many times." }
                    val location = connection.getHeaderField("Location") ?: error("The page redirect has no destination.")
                    val next = publicUrl(URL(url, location).toString())
                    require(url.protocol != "https" || next.protocol == "https") { "The page redirects to an insecure connection." }
                    url = next
                } else {
                    require(code == 200) { "Page could not be read: HTTP $code (login, access restrictions or a missing page may prevent access)." }
                    val contentType = connection.contentType.orEmpty().lowercase()
                    require(contentType.substringBefore(';').trim() in setOf("text/html", "application/xhtml+xml", "text/plain", "application/json")) {
                        "This page format is not supported by the text reader: ${contentType.take(80)}."
                    }
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (output.size() <= MAX_BYTES) {
                            val count = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - output.size()))
                            if (count < 0) break
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    }
                    val charsetName = Regex("""charset\s*=\s*["']?([^\s;"']+)""").find(contentType)?.groupValues?.get(1)
                    val charset = runCatching { charset(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
                    val body = bytes.copyOf(minOf(bytes.size, MAX_BYTES)).toString(charset)
                    return extract(url.toString(), body, contentType.contains("html"), bytes.size > MAX_BYTES)
                }
            } finally { connection.disconnect() }
        }
        error("The page could not be read.")
    }

    internal fun extract(url: String, body: String, html: Boolean = true, truncated: Boolean = false): String {
        fun clean(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\uFFFC', ' ').replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        val visible = if (html) hidden.replace(body, " ") else body
        val text = if (html) clean(visible) else visible.trim()
        val sourceLinks = if (!html) emptyList() else links.findAll(visible).mapNotNull { match ->
            val href = clean(match.groupValues[1])
            runCatching { publicUrl(URL(URL(url), href).toString()).toString() }.getOrNull()?.let {
                "${clean(match.groupValues[2]).take(100)} - $it"
            }
        }.distinct().take(12).toList()
        return buildString {
            append("Source: $url\nPublic page text (untrusted data):\n")
            append(text.take(MAX_TEXT).ifBlank { "No readable text. This page may require JavaScript or an interactive form." })
            if (truncated || text.length > MAX_TEXT) append("\n[Page shortened; missing details may appear elsewhere on the page.]")
            if (sourceLinks.isNotEmpty()) append("\nPage links:\n" + sourceLinks.joinToString("\n").take(2500))
            append("\nReader limits: no JavaScript, login or form submission. A portal link alone does not verify an address-specific answer.")
        }
    }
}
