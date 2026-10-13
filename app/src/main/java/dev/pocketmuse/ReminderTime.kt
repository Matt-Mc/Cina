package dev.pocketmuse

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal class ToolDetailsRequiredException(message: String) : IllegalArgumentException(message)

/** Conservative parser: unsupported or ambiguous phrases are returned to the editable time picker. */
internal object ReminderTime {
    /** Model-supplied times must come from the user's words, not the system clock or a tool result. */
    fun isUserProvided(phrase: String, userText: String): Boolean {
        fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val time = normalize(phrase).removePrefix("in ")
        return time.isNotBlank() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(time)}(?![\\p{L}\\p{N}])").containsMatchIn(normalize(userText))
    }

    fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): Instant {
        val input = text.trim()
        runCatching { OffsetDateTime.parse(input).toInstant() }.getOrNull()?.let { return future(it, now) }
        runCatching { ZonedDateTime.parse(input).toInstant() }.getOrNull()?.let { return future(it, now) }
        val phrase = input.lowercase(Locale.ROOT)
        Regex("(?:in\\s+)?(\\d{1,4})\\s+(minute|hour|day)s?").matchEntire(phrase)?.let {
            val amount = it.groupValues[1].toLong()
            require(amount > 0) { "Choose a future time." }
            return when (it.groupValues[2]) {
                "minute" -> now.plusMinutes(amount)
                "hour" -> now.plusHours(amount)
                else -> now.plusDays(amount)
            }.toInstant()
        }
        val match = Regex("(today|tomorrow|(?:next )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|\\d{4}-\\d{2}-\\d{2})(?: at|,)? (noon|midnight|\\d{1,2}(?::\\d{2})?(?:\\s?[ap]m)?)").matchEntire(phrase)
            ?: throw IllegalArgumentException("Choose a date and time. Try ‘tomorrow at 3 pm’ or use the date picker.")
        val day = match.groupValues[1]
        val date = when (day) {
            "today" -> now.toLocalDate()
            "tomorrow" -> now.toLocalDate().plusDays(1)
            else -> if (day.first().isDigit()) LocalDate.parse(day) else now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.valueOf(day.removePrefix("next ").uppercase(Locale.ROOT))))
        }
        val clock = match.groupValues[2]
        val time = when (clock) {
            "noon" -> LocalTime.NOON
            "midnight" -> LocalTime.MIDNIGHT
            else -> {
                val parts = Regex("(\\d{1,2})(?::(\\d{2}))?\\s?([ap]m)?").matchEntire(clock)!!
                var hour = parts.groupValues[1].toInt()
                val minute = parts.groupValues[2].ifBlank { "0" }.toInt()
                val meridiem = parts.groupValues[3]
                if (meridiem.isNotEmpty()) { require(hour in 1..12); hour = hour % 12 + if (meridiem == "pm") 12 else 0 }
                else require(parts.groupValues[2].isNotBlank() && (hour == 0 || hour in 13..23)) { "Is that AM or PM? Choose a time." }
                LocalTime.of(hour, minute)
            }
        }
        val local = LocalDateTime.of(date, time)
        val offsets = now.zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "This time changes with daylight saving. Choose an unambiguous time." }
        return future(local.toInstant(offsets.single()), now)
    }
    private fun future(instant: Instant, now: ZonedDateTime): Instant = instant.also {
        require(it.isAfter(now.toInstant())) { "Choose a future date and time." }
    }
    fun label(iso: String): String = runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · h:mm a z"))
    }.getOrDefault(iso)
}
