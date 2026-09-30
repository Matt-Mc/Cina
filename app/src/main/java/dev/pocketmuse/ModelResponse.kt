package dev.pocketmuse

/** Separates optional model reasoning from the answer without discarding the original response. */
internal data class ModelResponse(val answer: String, val thinking: String)

internal fun splitModelResponse(raw: String): ModelResponse {
    val answer = StringBuilder()
    val thinking = StringBuilder()
    var position = 0
    while (position < raw.length) {
        val start = raw.indexOf("<think>", position, ignoreCase = true)
        if (start < 0) {
            answer.append(raw, position, raw.length)
            break
        }
        answer.append(raw, position, start)
        val contentStart = start + "<think>".length
        val end = raw.indexOf("</think>", contentStart, ignoreCase = true)
        if (end < 0) {
            thinking.append(raw, contentStart, raw.length)
            break
        }
        if (thinking.isNotEmpty()) thinking.append("\n\n")
        thinking.append(raw, contentStart, end)
        position = end + "</think>".length
    }
    return ModelResponse(answer.toString().trim(), thinking.toString().trim())
}

/** Hide tool syntax from the first streamed prefix, including incomplete opening tags. */
internal fun visibleModelResponse(raw: String): String {
    val answer = splitModelResponse(raw).answer.trimStart()
    return if (answer.isNotEmpty() &&
        ("[tool]".startsWith(answer, ignoreCase = true) || answer.startsWith("[tool", ignoreCase = true))) "" else raw
}
