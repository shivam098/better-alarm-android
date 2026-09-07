package com.alarmy.core.state

import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WakeStatisticsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val calculator = WakeStatisticsCalculator(zone)
    private val base: LocalDate = LocalDate.of(2026, 5, 1)

    private fun occurrence(
        dayOffset: Long,
        outcome: AlarmOutcome,
        snoozeCount: Int = 0,
        timeToCompleteMs: Long? = null,
        latenessSeconds: Long = 0
    ): AlarmOccurrence {
        val scheduled = base.plusDays(dayOffset)
            .atTime(LocalTime.of(7, 0))
            .atZone(zone)
            .toInstant()

        return AlarmOccurrence(
            alarmId = "alarm-1",
            occurrenceKey = "alarm-1-$dayOffset",
            scheduledAtEpochMillis = scheduled.toEpochMilli(),
            outcome = outcome,
            missionType = MissionType.MATH,
            difficulty = MissionDifficulty.MEDIUM,
            snoozeCount = snoozeCount,
            timeToCompleteMs = timeToCompleteMs,
            latenessSeconds = latenessSeconds
        )
    }

    private fun now(dayOffset: Long): Instant =
        base.plusDays(dayOffset).atTime(LocalTime.of(12, 0)).atZone(zone).toInstant()

    @Test
    fun `no history means no statistics`() {
        val stats = calculator.compute(emptyList(), now(0))

        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
        assertEquals(0, stats.reliabilityPercent)
    }

    @Test
    fun `consecutive verified wakes build a streak`() {
        val history = (0L..4L).map { occurrence(it, AlarmOutcome.COMPLETED) }

        val stats = calculator.compute(history, now(4))

        assertEquals(5, stats.currentStreak)
        assertEquals(5, stats.longestStreak)
        assertEquals(5, stats.totalVerifiedWakes)
    }

    @Test
    fun `one missed day is forgiven by the grace allowance`() {
        // Days 0,1,3,4,5 verified; day 2 missed entirely. Today is day 5.
        val history = listOf(0L, 1L, 3L, 4L, 5L).map { occurrence(it, AlarmOutcome.COMPLETED) }

        val stats = calculator.compute(history, now(5))

        assertEquals(5, stats.currentStreak)
    }

    @Test
    fun `too many gaps end the streak`() {
        // Days 1,3,5 verified. Today is day 5. Two gaps, but only one grace.
        val history = listOf(1L, 3L, 5L).map { occurrence(it, AlarmOutcome.COMPLETED) }

        val stats = calculator.compute(history, now(5))

        assertEquals(2, stats.currentStreak)
    }

    @Test
    fun `a gap deep inside a long run is forgiven, but a recent one is not`() {
        // The allowance accrues as the streak is walked backwards, so an old gap
        // buried in a long run is affordable while an identical gap in the last
        // few days is not. That asymmetry is intentional: a run you are still
        // actively keeping should not be rescued by history.
        val deepGap = (listOf(0L) + (3L..16L)).map { occurrence(it, AlarmOutcome.COMPLETED) }
        val recentGap = ((0L..13L) + listOf(16L)).map { occurrence(it, AlarmOutcome.COMPLETED) }

        assertEquals(15, calculator.compute(deepGap, now(16)).currentStreak)
        assertEquals(1, calculator.compute(recentGap, now(16)).currentStreak)
    }

    @Test
    fun `a lapsed streak resets to zero`() {
        val history = (0L..4L).map { occurrence(it, AlarmOutcome.COMPLETED) }

        // Nothing verified for a week.
        val stats = calculator.compute(history, now(12))

        assertEquals(0, stats.currentStreak)
        assertEquals(5, stats.longestStreak, "the record is still remembered")
    }

    @Test
    fun `the longest streak is never smaller than the current one`() {
        val history = listOf(0L, 1L, 3L, 4L, 5L).map { occurrence(it, AlarmOutcome.COMPLETED) }

        val stats = calculator.compute(history, now(5))

        assertTrue(stats.longestStreak >= stats.currentStreak)
    }

    @Test
    fun `dismissing without the mission does not count as a wake`() {
        val history = listOf(
            occurrence(0, AlarmOutcome.COMPLETED),
            occurrence(1, AlarmOutcome.DISMISSED_UNVERIFIED),
            occurrence(2, AlarmOutcome.COMPLETED)
        )

        val stats = calculator.compute(history, now(2))

        assertEquals(2, stats.totalVerifiedWakes)
        assertEquals(1, stats.totalMissed)
        assertEquals(66, stats.reliabilityPercent)
    }

    @Test
    fun `averages are computed over the right subsets`() {
        val history = listOf(
            occurrence(0, AlarmOutcome.COMPLETED, snoozeCount = 2, timeToCompleteMs = 20_000),
            occurrence(1, AlarmOutcome.COMPLETED, snoozeCount = 0, timeToCompleteMs = 40_000),
            occurrence(2, AlarmOutcome.MISSED, snoozeCount = 4)
        )

        val stats = calculator.compute(history, now(2))

        assertEquals(30.0, stats.averageMissionSeconds, "mission time averages verified wakes only")
        assertEquals(2.0, stats.snoozesPerWake, "snoozes average over every occurrence")
    }

    @Test
    fun `lateness is averaged over alarms that actually ran late`() {
        val history = listOf(
            occurrence(0, AlarmOutcome.COMPLETED, latenessSeconds = 0),
            occurrence(1, AlarmOutcome.COMPLETED, latenessSeconds = 60),
            occurrence(2, AlarmOutcome.COMPLETED, latenessSeconds = 120)
        )

        val stats = calculator.compute(history, now(2))

        assertEquals(90.0, stats.averageLatenessSeconds)
    }

    @Test
    fun `duplicate occurrences on one day count once towards the streak`() {
        val history = listOf(
            occurrence(0, AlarmOutcome.COMPLETED),
            occurrence(0, AlarmOutcome.COMPLETED),
            occurrence(1, AlarmOutcome.COMPLETED)
        )

        val stats = calculator.compute(history, now(1))

        assertEquals(2, stats.currentStreak)
    }
}
