package com.alarmy.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/**
 * Day of week.
 *
 * Backed by [java.time.DayOfWeek] rather than a raw Int, because the iOS build's
 * Calendar-style 1=Sunday numbering is a genuine source of off-by-one bugs and
 * there is no reason to carry it onto a platform that has a proper type.
 */
@Serializable
enum class Weekday(val iso: Int) {
    MONDAY(1), TUESDAY(2), WEDNESDAY(3), THURSDAY(4), FRIDAY(5), SATURDAY(6), SUNDAY(7);

    val dayOfWeek: DayOfWeek get() = DayOfWeek.of(iso)

    val shortName: String
        get() = when (this) {
            MONDAY -> "Mon"
            TUESDAY -> "Tue"
            WEDNESDAY -> "Wed"
            THURSDAY -> "Thu"
            FRIDAY -> "Fri"
            SATURDAY -> "Sat"
            SUNDAY -> "Sun"
        }

    companion object {
        val WEEKDAYS: Set<Weekday> = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val WEEKEND: Set<Weekday> = setOf(SATURDAY, SUNDAY)

        fun from(day: DayOfWeek): Weekday = entries.first { it.iso == day.value }
    }
}

/** Human summary used in the alarm list ("Weekdays", "Mon, Wed, Fri", ...). */
fun Set<Weekday>.summary(): String = when {
    isEmpty() -> "Once"
    size == 7 -> "Every day"
    this == Weekday.WEEKDAYS -> "Weekdays"
    this == Weekday.WEEKEND -> "Weekends"
    else -> Weekday.entries.filter { contains(it) }.joinToString(", ") { it.shortName }
}
