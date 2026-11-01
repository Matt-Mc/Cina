package dev.pocketmuse

/** Conservative app policy, independent of the small model's tool choices. */
internal object HomeRouting {
    private val analysis = Regex("^(please\\s+)?(compare|explain|summari[sz]e|analy[sz]e|draft|review|evaluate|outline|what|why|how)\\b", RegexOption.IGNORE_CASE)
    private val action = Regex("\\b(save|send|email|remind|schedule|create|delete|update|open|search|find|remember|forget|post|buy|book|download|connect|execute|run|install|move|rename|archive)\\b", RegexOption.IGNORE_CASE)
    fun automatic(mode: ResponseMode, request: String, online: Boolean, assigned: String?): String? =
        assigned?.takeIf { online && mode == ResponseMode.DEEP && analysis.containsMatchIn(request.trim()) && !action.containsMatchIn(request) }
}
