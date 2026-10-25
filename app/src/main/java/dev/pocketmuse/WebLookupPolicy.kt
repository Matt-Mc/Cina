package dev.pocketmuse

/** Require a real search for clear lookup intent; ambiguous requests keep the normal routing choice. */
internal object WebLookupPolicy {
    private val negative = Regex("""\b(?:do not|don't|without|no need to|stop)\s+(?:search|searching|browse|browsing|look(?:ing)? up)\b""", RegexOption.IGNORE_CASE)
    private val localSource = Regex("""\b(?:(?:my|your|saved|attached|local)\s+(?:notes?|files?|documents?|memor(?:y|ies)|reminders?|schedules?)|available tools|procedures?|skills?|connections)\b""", RegexOption.IGNORE_CASE)
    private val explicit = Regex("""^\s*(?:(?:please|can you|could you|would you|i need you to)\s+)*(?:search(?:\s+(?:the\s+)?(?:web|internet|online))?(?:\s+(?:for|about))?|look\s+up|find\s+information\s+(?:on|about))\b""", RegexOption.IGNORE_CASE)
    private val directAction = Regex("""^\s*(?:(?:please|can you|could you|would you|help me|i need you to)\s+)*(?:save|create|set|add|schedule|remind|remember|delete|update|send)\b""", RegexOption.IGNORE_CASE)
    private val collection = Regex("""\b(?:garbage|waste|trash|recycling|green bin)\b""", RegexOption.IGNORE_CASE)
    private val collectionTime = Regex("""\b(?:schedule|day|pick.?up|collect(?:ion|ed)?|when)\b""", RegexOption.IGNORE_CASE)
    private val street = Regex("""\b\d{1,6}\s+[\p{L}\p{N} .'-]+\s(?:street|st|avenue|ave|road|rd|drive|dr|lane|ln|boulevard|blvd|way|court|ct|crescent|cres|place|pl|terrace|trail|rue|chemin)\b""", RegexOption.IGNORE_CASE)
    private val followUp = Regex("""^\s*(?:are you (?:still )?(?:searching(?: for (?:it|that))?|checking)|did you find (?:it|anything)|any updates?)\s*[?.!]*\s*$""", RegexOption.IGNORE_CASE)
    private val furtherAction = Regex("""\b(?:save|create|set|add|send|remind|remember|delete|update|reschedule|draft|share|schedule\s+(?:it|this|that|a|an|the|my))\b""", RegexOption.IGNORE_CASE)

    fun lookupOnly(request: String, recent: String = ""): Boolean = requiresSearch(request, recent) &&
        ResponsePolicy.resolve(ResponseMode.AUTO, request) == ResponseMode.QUICK &&
        !furtherAction.containsMatchIn(if (followUp.matches(request)) recent else request)

    fun requiresSearch(request: String, recent: String = ""): Boolean {
        if (TurnDecision.isWritingRequest(request) || directAction.containsMatchIn(request) || negative.containsMatchIn(request) || localSource.containsMatchIn(request)) return false
        if (followUp.matches(request)) return recent.isNotBlank() && requiresSearch(recent)
        explicit.find(request)?.let { command ->
            val subject = request.substring(command.range.last + 1).trim(' ', '.', '?', '!')
            if (subject.isNotBlank() && (subject.lowercase() !in setOf("it", "that", "this", "something") || recent.isNotBlank())) return true
        }
        return collection.containsMatchIn(request) && collectionTime.containsMatchIn(request) && street.containsMatchIn(request)
    }
}
