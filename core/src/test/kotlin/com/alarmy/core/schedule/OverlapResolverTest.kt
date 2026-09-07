package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapResolverTest {

    private val base: Instant = Instant.parse("2026-05-11T07:00:00Z")

    private fun pending(
        name: String,
        offsetSeconds: Long,
        missionType: MissionType = MissionType.MATH,
        difficulty: MissionDifficulty = MissionDifficulty.MEDIUM
    ): PendingAlarm {
        val fireDate = base.plusSeconds(offsetSeconds)
        val alarm = Alarm(
            id = name,
            name = name,
            mission = MissionConfig(missionType, difficulty, 3)
        )
        return PendingAlarm(
            alarm = alarm,
            occurrence = ScheduledOccurrence(alarm.id, fireDate, "$name-key")
        )
    }

    @Test
    fun `alarms far apart stay separate`() {
        val sessions = OverlapResolver().resolve(listOf(pending("a", 0), pending("b", 600)))

        assertEquals(2, sessions.size)
        assertTrue(sessions.none { it.isMerged })
    }

    @Test
    fun `alarms within the merge window become one session`() {
        val sessions = OverlapResolver().resolve(listOf(pending("a", 0), pending("b", 30)))

        assertEquals(1, sessions.size)
        assertTrue(sessions[0].isMerged)
        assertEquals(2, sessions[0].members.size)
    }

    @Test
    fun `a merged session takes the earliest time`() {
        val sessions = OverlapResolver().resolve(listOf(pending("b", 45), pending("a", 0)))

        assertEquals(base, sessions[0].fireDate)
        assertEquals("a-key", sessions[0].key)
    }

    @Test
    fun `a merged session takes the hardest mission`() {
        val sessions = OverlapResolver().resolve(
            listOf(
                pending("easy", 0, MissionType.MATH, MissionDifficulty.VERY_EASY),
                pending("hard", 30, MissionType.SQUAT, MissionDifficulty.HARD)
            )
        )

        assertEquals(MissionType.SQUAT, sessions[0].mission.type)
        assertEquals(MissionDifficulty.HARD, sessions[0].mission.difficulty)
    }

    @Test
    fun `every merged alarm is named, so none is hidden`() {
        val sessions = OverlapResolver().resolve(listOf(pending("Gym", 0), pending("Class", 30)))

        assertEquals("Gym + Class", sessions[0].displayName)
        assertEquals(listOf("Gym", "Class"), sessions[0].alarmIds)
    }

    @Test
    fun `merging is anchored, so a chain cannot collapse without bound`() {
        // Each alarm is 80s after the previous — inside the 90s window pairwise,
        // but the run must not fold into a single session.
        val chain = (0..5).map { pending("a$it", it * 80L) }

        val sessions = OverlapResolver().resolve(chain)

        assertTrue(sessions.size > 1, "transitive merging would have produced one session")
        assertTrue(
            sessions.all { session ->
                val span = Duration.between(
                    session.members.first().occurrence.fireDate,
                    session.members.last().occurrence.fireDate
                )
                span <= Duration.ofSeconds(90)
            },
            "no session may span more than the merge window"
        )
    }

    @Test
    fun `exactly at the window boundary still merges`() {
        val sessions = OverlapResolver().resolve(listOf(pending("a", 0), pending("b", 90)))
        assertEquals(1, sessions.size)
    }

    @Test
    fun `one second past the boundary does not merge`() {
        val sessions = OverlapResolver().resolve(listOf(pending("a", 0), pending("b", 91)))
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a later session is blocked while an earlier one is unresolved`() {
        val resolver = OverlapResolver()
        val sessions = resolver.resolve(listOf(pending("a", 0), pending("b", 600)))
        val (first, second) = sessions

        assertTrue(resolver.isBlocked(second, listOf(first)))
        assertFalse(resolver.isBlocked(first, listOf(second)))
        assertFalse(resolver.isBlocked(second, emptyList()))
    }

    @Test
    fun `an empty input produces no sessions`() {
        assertTrue(OverlapResolver().resolve(emptyList()).isEmpty())
    }
}

class MissedWindowPolicyTest {

    private val scheduled: Instant = Instant.parse("2026-05-11T07:00:00Z")

    @Test
    fun `an alarm that is not yet due is left alone`() {
        val decision = MissedWindowPolicy().decide(scheduled, scheduled.minusSeconds(60))
        assertEquals(MissedWindowDecision.NotYetDue, decision)
    }

    @Test
    fun `a recently missed alarm rings inside the grace period`() {
        val decision = MissedWindowPolicy(graceMinutes = 15)
            .decide(scheduled, scheduled.plusSeconds(10 * 60))

        assertTrue(decision is MissedWindowDecision.TriggerNow)
    }

    @Test
    fun `an old missed alarm is recorded rather than rung`() {
        val decision = MissedWindowPolicy(graceMinutes = 15)
            .decide(scheduled, scheduled.plusSeconds(45 * 60))

        assertTrue(decision is MissedWindowDecision.SkipAndRecordMissed)
    }

    @Test
    fun `exactly at the grace boundary still rings`() {
        val decision = MissedWindowPolicy(graceMinutes = 15)
            .decide(scheduled, scheduled.plusSeconds(15 * 60))

        assertTrue(decision is MissedWindowDecision.TriggerNow)
    }

    @Test
    fun `always skip never rings, however recent`() {
        val decision = MissedWindowPolicy(MissedWindowRule.ALWAYS_SKIP)
            .decide(scheduled, scheduled.plusSeconds(30))

        assertTrue(decision is MissedWindowDecision.SkipAndRecordMissed)
    }

    @Test
    fun `always trigger rings however late`() {
        val decision = MissedWindowPolicy(MissedWindowRule.ALWAYS_TRIGGER)
            .decide(scheduled, scheduled.plusSeconds(72 * 3600))

        assertTrue(decision is MissedWindowDecision.TriggerNow)
    }

    @Test
    fun `every outcome except not-yet-due is disclosed to the user`() {
        val policy = MissedWindowPolicy()

        val triggered = policy.decide(scheduled, scheduled.plusSeconds(300))
        val skipped = policy.decide(scheduled, scheduled.plusSeconds(3 * 3600))

        assertTrue(policy.disclosure(triggered, "Wake")!!.contains("late"))
        assertTrue(policy.disclosure(skipped, "Wake")!!.contains("missed"))
        assertEquals(null, policy.disclosure(MissedWindowDecision.NotYetDue, "Wake"))
    }

    @Test
    fun `durations are described in plain language`() {
        assertEquals("less than a minute", MissedWindowPolicy.humanDuration(Duration.ofSeconds(30)))
        assertEquals("1 minute", MissedWindowPolicy.humanDuration(Duration.ofMinutes(1)))
        assertEquals("42 minutes", MissedWindowPolicy.humanDuration(Duration.ofMinutes(42)))
        assertEquals("2 hours", MissedWindowPolicy.humanDuration(Duration.ofHours(2)))
        assertEquals("1h 30m", MissedWindowPolicy.humanDuration(Duration.ofMinutes(90)))
    }
}
