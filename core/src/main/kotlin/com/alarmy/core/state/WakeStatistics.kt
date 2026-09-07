package com.alarmy.core.state

import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.AlarmOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

data class WakeStatistics(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalVerifiedWakes: Int = 0,
    val totalMissed: Int = 0,
    val averageMissionSeconds: Double = 0.0,
    val averageLatenessSeconds: Double = 0.0,
    val snoozesPerWake: Double = 0.0
) {
    val reliabilityPercent: Int
        get() {
            val total = totalVerifiedWakes + totalMissed
            return if (total == 0) 0 else ((totalVerifiedWakes * 100.0) / total).toInt()
        }
}

/**
 * Derives wake statistics from history.
 *
 * The streak rules are deliberately forgiving. A streak that snaps the first
 * time someone takes a day off is a streak people abandon, and a discarded
 * streak motivates nobody. One grace day is allowed per full week of streak,
 * which rewards consistency without punishing a single lie-in.
 */
class WakeStatisticsCalculator(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun compute(occurrences: List<AlarmOccurrence>, now: Instant): WakeStatistics {
        if (occurrences.isEmpty()) return WakeStatistics()

        val verified = occurrences.filter { it.outcome == AlarmOutcome.COMPLETED }
        val missed = occurrences.filter {
            it.outcome == AlarmOutcome.MISSED || it.outcome == AlarmOutcome.DISMISSED_UNVERIFIED
        }

        val verifiedDays = verified
            .map { Instant.ofEpochMilli(it.scheduledAtEpochMillis).atZone(zone).toLocalDate() }
            .toSortedSet()

        val today = now.atZone(zone).toLocalDate()
        val current = currentStreak(verifiedDays.toList(), today)
        val longest = max(longestStreak(verifiedDays.toList()), current)

        val missionDurations = verified.mapNotNull { it.timeToCompleteMs }.map { it / 1000.0 }
        val latenessValues = occurrences.map { it.latenessSeconds }.filter { it > 0 }
        val snoozes = occurrences.sumOf { it.snoozeCount }

        return WakeStatistics(
            currentStreak = current,
            longestStreak = longest,
            totalVerifiedWakes = verified.size,
            totalMissed = missed.size,
            averageMissionSeconds = missionDurations.averageOrZero(),
            averageLatenessSeconds = latenessValues.map { it.toDouble() }.averageOrZero(),
            snoozesPerWake = if (occurrences.isEmpty()) 0.0
            else snoozes.toDouble() / occurrences.size
        )
    }

    /**
     * Counts back from today, spending a grace day for each gap.
     *
     * The allowance grows with the streak: one grace, plus one more for every
     * seven days already banked. A 30-day streak has more slack than a 3-day
     * streak, which is both fairer and better motivation.
     */
    internal fun currentStreak(days: List<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0

        val descending = days.sortedDescending()
        val mostRecent = descending.first()

        // A streak stays alive only if the last verified wake is today or
        // yesterday; anything older has already lapsed.
        if (ChronoUnit.DAYS.between(mostRecent, today) > 1) return 0

        var streak = 1
        var gracesUsed = 0
        var cursor = mostRecent

        for (day in descending.drop(1)) {
            val gap = ChronoUnit.DAYS.between(day, cursor).toInt()
            when {
                gap == 1 -> {
                    streak += 1
                    cursor = day
                }
                gap > 1 -> {
                    val allowed = 1 + (streak / 7)
                    val needed = gap - 1
                    if (gracesUsed + needed <= allowed) {
                        gracesUsed += needed
                        streak += 1
                        cursor = day
                    } else {
                        return streak
                    }
                }
                // gap == 0 means duplicate days; ignore.
                else -> Unit
            }
        }
        return streak
    }

    internal fun longestStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0

        val ascending = days.sorted()
        var best = 1
        var run = 1

        for (index in 1 until ascending.size) {
            val gap = ChronoUnit.DAYS.between(ascending[index - 1], ascending[index]).toInt()
            if (gap == 1) {
                run += 1
                best = max(best, run)
            } else if (gap > 1) {
                run = 1
            }
        }
        return best
    }
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
