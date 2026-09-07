package com.alarmy.app.ui.common

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.Weekday
import com.alarmy.core.model.summary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {

    fun time(hour: Int, minute: Int, use24Hour: Boolean): String =
        if (use24Hour) {
            String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        } else {
            val suffix = if (hour < 12) "AM" else "PM"
            val display = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            String.format(Locale.getDefault(), "%d:%02d %s", display, minute, suffix)
        }

    fun repeatSummary(alarm: Alarm): String =
        if (alarm.repeatDays.isEmpty()) "Once" else alarm.repeatDays.summary()

    /** "in 7h 42m" — the thing a user actually wants to know at bedtime. */
    fun countdown(from: Instant, to: Instant): String {
        val duration = Duration.between(from, to)
        if (duration.isNegative || duration.isZero) return "now"
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        return when {
            hours >= 24 -> {
                val days = hours / 24
                "in ${days}d ${hours % 24}h"
            }
            hours > 0 -> "in ${hours}h ${minutes}m"
            minutes > 0 -> "in ${minutes}m"
            else -> "in under a minute"
        }
    }

    fun dateTime(instant: Instant, zone: ZoneId, use24Hour: Boolean): String {
        val pattern = if (use24Hour) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .withZone(zone)
            .format(instant)
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    val orderedWeekdays: List<Weekday> = Weekday.entries.toList()
}
