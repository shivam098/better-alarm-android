package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.OccurrenceKey
import com.alarmy.core.model.ScheduleMode
import com.alarmy.core.model.Weekday
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One computed firing of an alarm. */
data class ScheduledOccurrence(
    val alarmId: String,
    val fireDate: Instant,
    val key: String
)

/**
 * Computes when an alarm should next fire.
 *
 * ## Daylight saving
 *
 * All DST behaviour is delegated to `java.time`, which is the entire reason
 * this class does no arithmetic on epoch milliseconds. Adding 24 * 60 * 60 *
 * 1000 to get "tomorrow at 07:00" is wrong twice a year, and is the classic way
 * an alarm app fails on exactly the two mornings people notice.
 *
 * The two interesting cases, both handled by [ZonedDateTime.of]:
 *
 *  * **Spring forward (a gap).** 02:30 does not exist on the day the clocks
 *    jump 02:00 -> 03:00. `ZonedDateTime.of` shifts the time forward by the
 *    length of the gap, so the alarm rings at 03:30 rather than being silently
 *    skipped. An alarm that does not ring is the worst possible outcome, so
 *    ringing at a shifted time is the right trade.
 *
 *  * **Fall back (an overlap).** 01:30 happens twice on the day the clocks go
 *    back. `ZonedDateTime.of` resolves to the *earlier* offset, so the alarm
 *    fires once, on the first pass, and does not ring twice.
 */
class AlarmScheduleCalculator(private val zone: ZoneId = ZoneId.systemDefault()) {

    /** The next instant this alarm should ring, or `null` if it never will. */
    fun nextFireDate(alarm: Alarm, after: Instant): Instant? {
        if (!alarm.isEnabled) return null

        return when (alarm.scheduleMode) {
            ScheduleMode.ABSOLUTE ->
                alarm.absoluteInstant?.takeIf { it.isAfter(after) }

            ScheduleMode.WALL_CLOCK -> {
                val time = LocalTime.of(alarm.hour, alarm.minute)
                val startDate = after.atZone(zone).toLocalDate()

                // 0..7 covers today plus a full week, which is enough to find
                // the next match for any set of repeat days.
                (0L..7L).firstNotNullOfOrNull { offset ->
                    val date = startDate.plusDays(offset)
                    val dayMatches = !alarm.isRepeating ||
                        Weekday.from(date.dayOfWeek) in alarm.repeatDays

                    if (!dayMatches) {
                        null
                    } else {
                        ZonedDateTime.of(date, time, zone)
                            .toInstant()
                            .takeIf { it.isAfter(after) }
                    }
                }
            }
        }
    }

    /** The next [limit] firings, used to pre-schedule repeating alarms. */
    fun nextFireDates(alarm: Alarm, after: Instant, limit: Int): List<Instant> {
        if (limit <= 0 || !alarm.isEnabled) return emptyList()

        val results = mutableListOf<Instant>()
        var cursor = after

        while (results.size < limit) {
            val next = nextFireDate(alarm, cursor) ?: break
            // Defensive: the search must always move forward. If it ever does
            // not, stop rather than loop forever.
            if (!next.isAfter(cursor)) break

            results += next
            cursor = next

            if (alarm.scheduleMode == ScheduleMode.ABSOLUTE) break
            if (!alarm.isRepeating) break
        }
        return results
    }

    /** Next firings as idempotent occurrences. */
    fun occurrences(alarm: Alarm, after: Instant, limit: Int): List<ScheduledOccurrence> =
        nextFireDates(alarm, after, limit).map { fireDate ->
            ScheduledOccurrence(
                alarmId = alarm.id,
                fireDate = fireDate,
                key = OccurrenceKey.make(alarm.id, fireDate, zone)
            )
        }

    companion object {
        /**
         * Deduplicates occurrences by key.
         *
         * Reboot re-arm, time-zone changes and repeated reconciliation all route
         * through here, which is why none of them can create a duplicate alarm.
         */
        fun deduplicate(occurrences: List<ScheduledOccurrence>): List<ScheduledOccurrence> =
            occurrences.distinctBy { it.key }
    }
}
