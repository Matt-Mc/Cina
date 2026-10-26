package dev.pocketmuse

import org.json.JSONArray
import org.json.JSONObject

/** The model chooses from observed URLs instead of reconstructing long addresses token by token. */
internal class WebSources {
    private val urls = linkedSetOf<String>()

    fun clear() = urls.clear()
    fun remember(text: String) {
        Regex("""https?://[^\s<>"']+""").findAll(text).forEach { match ->
            var raw = match.value.trimEnd('.', ',', ';', ']', '}')
            while (raw.endsWith(')') && raw.count { it == ')' } > raw.count { it == '(' }) raw = raw.dropLast(1)
            runCatching { canonical(raw) }.getOrNull()?.takeIf { it.length <= 2000 && urls.size < 32 }?.let(urls::add)
        }
    }

    fun contains(url: String): Boolean = runCatching { canonical(url) in urls }.getOrDefault(false)

    fun constrain(schema: String): String {
        val root = JSONObject(schema)
        val choices = root.getJSONArray("oneOf")
        val constrained = JSONArray()
        for (index in 0 until choices.length()) {
            val choice = choices.getJSONObject(index)
            if (choice.getJSONObject("properties").getJSONObject("name").optString("const") == "web_read") {
                if (urls.isEmpty()) continue
                choice.getJSONObject("properties").getJSONObject("arguments").getJSONObject("properties")
                    .getJSONObject("url").put("enum", JSONArray(urls.toList()))
            }
            constrained.put(choice)
        }
        return root.put("oneOf", constrained).toString()
    }

    companion object {
        // Fragments do not change the HTTP response and must not bypass the duplicate-read guard.
        fun canonical(raw: String): String = WebPageReader.publicUrl(raw).toString().substringBefore('#')
    }
}
