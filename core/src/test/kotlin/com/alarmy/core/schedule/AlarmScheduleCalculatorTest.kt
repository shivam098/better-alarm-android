package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.OccurrenceKey
import com.alarmy.core.model.ScheduleMode
import com.alarmy.core.model.Weekday
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmScheduleCalculatorTest {

    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, text: String): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `one-time alarm fires today when the time has not passed`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0)

        val next = calculator.nextFireDate(alarm, at(utc, "2026-05-10T06:00:00"))

        assertEquals(at(utc, "2026-05-10T07:00:00"), next)
    }

    @Test
    fun `one-time alarm rolls to tomorrow once the time has passed`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0)

        val next = calculator.nextFireDate(alarm, at(utc, "2026-05-10T07:00:01"))

        assertEquals(at(utc, "2026-05-11T07:00:00"), next)
    }

    @Test
    fun `disabled alarm never fires`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, isEnabled = false)

        assertNull(calculator.nextFireDate(alarm, at(utc, "2026-05-10T06:00:00")))
    }

    @Test
    fun `repeating alarm only fires on selected days`() {
        val calculator = AlarmScheduleCalculator(utc)
        // 2026-05-10 is a Sunday.
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = setOf(Weekday.WEDNESDAY))

        val next = calculator.nextFireDate(alarm, at(utc, "2026-05-10T06:00:00"))

        assertEquals(at(utc, "2026-05-13T07:00:00"), next)
    }

    @Test
    fun `repeating alarm on today still fires today when time has not passed`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = setOf(Weekday.SUNDAY))

        val next = calculator.nextFireDate(alarm, at(utc, "2026-05-10T06:00:00"))

        assertEquals(at(utc, "2026-05-10T07:00:00"), next)
    }

    @Test
    fun `repeating alarm on today rolls a full week once passed`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = setOf(Weekday.SUNDAY))

        val next = calculator.nextFireDate(alarm, at(utc, "2026-05-10T08:00:00"))

        assertEquals(at(utc, "2026-05-17T07:00:00"), next)
    }

    @Test
    fun `weekday alarm produces five consecutive firings`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = Weekday.WEEKDAYS)

        // Monday 2026-05-11.
        val dates = calculator.nextFireDates(alarm, at(utc, "2026-05-11T06:00:00"), 5)

        assertEquals(5, dates.size)
        assertEquals(at(utc, "2026-05-11T07:00:00"), dates[0])
        assertEquals(at(utc, "2026-05-15T07:00:00"), dates[4])
    }

    @Test
    fun `absolute alarm ignores wall clock and fires once`() {
        val calculator = AlarmScheduleCalculator(newYork)
        val instant = at(utc, "2026-05-10T12:00:00")
        val alarm = Alarm(
            scheduleMode = ScheduleMode.ABSOLUTE,
            absoluteEpochMillis = instant.toEpochMilli()
        )

        assertEquals(instant, calculator.nextFireDate(alarm, instant.minusSeconds(60)))
        assertEquals(1, calculator.nextFireDates(alarm, instant.minusSeconds(60), 5).size)
        assertNull(calculator.nextFireDate(alarm, instant.plusSeconds(1)))
    }

    // --- Daylight saving -----------------------------------------------------

    @Test
    fun `spring forward does not skip an alarm scheduled inside the gap`() {
        // 2026-03-08: New York jumps 02:00 -> 03:00, so 02:30 does not exist.
        val calculator = AlarmScheduleCalculator(newYork)
        val alarm = Alarm(hour = 2, minute = 30)

        val next = calculator.nextFireDate(alarm, at(newYork, "2026-03-07T12:00:00"))

        assertNotNull(next)
        val local = next.atZone(newYork)
        assertEquals(8, local.dayOfMonth, "must still fire on the DST day")
        // Shifted forward past the gap rather than dropped.
        assertEquals(3, local.hour)
        assertEquals(30, local.minute)
        assertEquals(ZoneOffset.ofHours(-4), local.offset, "should be on EDT")
    }

    @Test
    fun `fall back fires once, on the first pass of the repeated hour`() {
        // 2026-11-01: New York repeats 01:00-02:00, so 01:30 happens twice.
        val calculator = AlarmScheduleCalculator(newYork)
        val alarm = Alarm(hour = 1, minute = 30, repeatDays = Weekday.entries.toSet())

        val dates = calculator.nextFireDates(alarm, at(newYork, "2026-10-31T12:00:00"), 2)

        assertEquals(2, dates.size)
        val first = dates[0].atZone(newYork)
        assertEquals(1, first.dayOfMonth)
        assertEquals(1, first.hour)
        assertEquals(30, first.minute)
        assertEquals(ZoneOffset.ofHours(-4), first.offset, "earlier offset — EDT, not EST")

        // The second firing is the next day, not the repeated 01:30 EST.
        assertEquals(2, dates[1].atZone(newYork).dayOfMonth)
    }

    @Test
    fun `wall clock alarm keeps its local time across a DST boundary`() {
        val calculator = AlarmScheduleCalculator(newYork)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = Weekday.entries.toSet())

        val dates = calculator.nextFireDates(alarm, at(newYork, "2026-03-06T12:00:00"), 4)

        // Every firing is 07:00 local, even though the UTC offset changes.
        dates.forEach { instant ->
            val local = instant.atZone(newYork)
            assertEquals(7, local.hour)
            assertEquals(0, local.minute)
        }
        // And the UTC gap across the spring-forward night is 23 hours, not 24.
        val gaps = dates.zipWithNext { a, b -> b.epochSecond - a.epochSecond }
        assertTrue(gaps.contains(23 * 3600L), "expected a 23-hour night, got $gaps")
    }

    // --- Idempotency ---------------------------------------------------------

    @Test
    fun `occurrence keys are stable across repeated computation`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = Weekday.WEEKDAYS)
        val now = at(utc, "2026-05-11T06:00:00")

        val first = calculator.occurrences(alarm, now, 5).map { it.key }
        val second = calculator.occurrences(alarm, now, 5).map { it.key }

        assertEquals(first, second)
    }

    @Test
    fun `deduplicate collapses a repeated reconciliation pass`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = Weekday.WEEKDAYS)
        val now = at(utc, "2026-05-11T06:00:00")

        val doubled = calculator.occurrences(alarm, now, 5) + calculator.occurrences(alarm, now, 5)

        assertEquals(10, doubled.size)
        assertEquals(5, AlarmScheduleCalculator.deduplicate(doubled).size)
    }

    @Test
    fun `request codes are stable, non-negative and distinct per occurrence`() {
        val calculator = AlarmScheduleCalculator(utc)
        val alarm = Alarm(hour = 7, minute = 0, repeatDays = Weekday.WEEKDAYS)
        val keys = calculator.occurrences(alarm, at(utc, "2026-05-11T06:00:00"), 5).map { it.key }

        val codes = keys.map { OccurrenceKey.requestCode(it) }

        assertTrue(codes.all { it >= 0 }, "PendingIntent request codes must be non-negative")
        assertEquals(codes.size, codes.toSet().size, "distinct occurrences need distinct codes")
        // Recomputed as if after a reboot: identical, so cancellation still works.
        assertEquals(codes, keys.map { OccurrenceKey.requestCode(it) })
    }

    @Test
    fun `watchdog and snooze keys resolve back to their parent`() {
        val parent = "abc-20260511-0700"

        assertEquals(parent, OccurrenceKey.rootOf(OccurrenceKey.watchdog(parent, 3)))
        assertEquals(parent, OccurrenceKey.rootOf(OccurrenceKey.snooze(parent, 2)))
        assertEquals(parent, OccurrenceKey.rootOf(parent))
    }
}
