package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeTest {
    private val now = ZonedDateTime.of(2026, 10, 13, 11, 41, 0, 0, ZoneId.of("America/Toronto"))
    @Test fun relativeTimesWithOrWithoutInResolveIdentically() {
        listOf("10 minutes", "in 10 minutes", " 10   MINUTES ").forEach {
            assertEquals(now.plusMinutes(10).toInstant(), ReminderTime.parse(it, now))
        }
        assertEquals(now.plusHours(1).toInstant(), ReminderTime.parse("1 hour", now))
        assertEquals(now.plusDays(2).toInstant(), ReminderTime.parse("2 days", now))
    }
    @Test fun missingAmbiguousAndNonFutureTimesAreRejected() {
        listOf("", "minutes", "0 minutes", "-1 minutes", "10", "10 minutes ago", "tomorrow", "11:30", "in 1.5 hours").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { ReminderTime.parse(it, now) }
        }
    }
}
