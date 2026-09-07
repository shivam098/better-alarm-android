package com.alarmy.core.state

import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnoozePolicyTest {

    @Test
    fun `snooze durations shorten on each use`() {
        val policy = SnoozePolicy(baseMinutes = 9)

        assertEquals(9, policy.durationMinutes(0))
        assertEquals(5, policy.durationMinutes(1))
        assertEquals(3, policy.durationMinutes(2))
    }

    @Test
    fun `duration is clamped once the ladder is exhausted`() {
        val policy = SnoozePolicy(baseMinutes = 9)
        assertEquals(3, policy.durationMinutes(7))
    }

    @Test
    fun `duration never drops below one minute`() {
        val policy = SnoozePolicy(baseMinutes = 1)
        assertTrue(policy.durationMinutes(2) >= 1)
    }

    @Test
    fun `snoozes are limited`() {
        val policy = SnoozePolicy(maxSnoozes = 3)

        assertTrue(policy.isAllowed(0))
        assertTrue(policy.isAllowed(2))
        assertFalse(policy.isAllowed(3))
        assertEquals(1, policy.remaining(2))
        assertEquals(0, policy.remaining(9))
    }
}

class AlarmStateMachineTest {

    private val key = "alarm-1-20260511-0700"
    private val mission = MissionConfig(MissionType.MATH, MissionDifficulty.MEDIUM, 3)

    private fun machine(guarded: Boolean = true, maxSnoozes: Int = 3) =
        AlarmStateMachine(
            snoozePolicy = SnoozePolicy(baseMinutes = 9, maxSnoozes = maxSnoozes),
            guarded = guarded,
            mission = mission
        )

    @Test
    fun `firing starts ringing and commits the guard chain`() {
        val (state, effects) = machine().reduce(AlarmState(), AlarmEvent.Fired(key))

        assertEquals(AlarmPhase.RINGING, state.phase)
        assertEquals(key, state.occurrenceKey)
        assertTrue(effects.contains(AlarmEffect.StartRinging(escalate = false)))
        assertTrue(effects.contains(AlarmEffect.ScheduleGuardChain))
        assertTrue(effects.contains(AlarmEffect.AcquireWakeLock))
    }

    @Test
    fun `an unguarded alarm commits no chain`() {
        val (_, effects) = machine(guarded = false).reduce(AlarmState(), AlarmEvent.Fired(key))
        assertFalse(effects.contains(AlarmEffect.ScheduleGuardChain))
    }

    @Test
    fun `completing the mission is the only path that cancels the chain`() {
        val m = machine()
        var state = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state
        state = m.reduce(state, AlarmEvent.MissionStarted).state

        val (final, effects) = m.reduce(state, AlarmEvent.MissionCompleted)

        assertEquals(AlarmPhase.COMPLETED, final.phase)
        assertEquals(AlarmOutcome.COMPLETED, final.outcome)
        assertTrue(effects.contains(AlarmEffect.CancelGuardChain))
        assertTrue(effects.contains(AlarmEffect.StopRinging))
        assertTrue(effects.contains(AlarmEffect.RecordOutcome(AlarmOutcome.COMPLETED)))
    }

    @Test
    fun `dismissing without the mission keeps the chain and is recorded as unverified`() {
        val m = machine()
        val ringing = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state

        val (state, effects) = m.reduce(ringing, AlarmEvent.DismissedWithoutMission)

        assertEquals(AlarmPhase.GUARD_PENDING, state.phase)
        assertEquals(AlarmOutcome.DISMISSED_UNVERIFIED, state.outcome)
        assertFalse(
            effects.contains(AlarmEffect.CancelGuardChain),
            "dismissing must never cancel the guard chain"
        )
        assertTrue(effects.contains(AlarmEffect.ScheduleGuardChain))
        assertTrue(effects.any { it is AlarmEffect.Disclose }, "the user must be told")
    }

    @Test
    fun `losing the ringing surface is treated exactly like a dismissal`() {
        val m = machine()
        val ringing = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state

        val dismissed = m.reduce(ringing, AlarmEvent.DismissedWithoutMission)
        val lost = m.reduce(ringing, AlarmEvent.SurfaceLost)

        assertEquals(dismissed.state.phase, lost.state.phase)
        assertEquals(dismissed.effects, lost.effects)
    }

    @Test
    fun `an unguarded alarm can simply be stopped`() {
        val m = machine(guarded = false)
        val ringing = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state

        val (state, effects) = m.reduce(ringing, AlarmEvent.DismissedWithoutMission)

        assertEquals(AlarmPhase.COMPLETED, state.phase)
        assertEquals(AlarmOutcome.DISMISSED_UNVERIFIED, state.outcome)
        assertFalse(effects.contains(AlarmEffect.ScheduleGuardChain))
    }

    @Test
    fun `a failed mission keeps the alarm ringing`() {
        val m = machine()
        var state = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state
        state = m.reduce(state, AlarmEvent.MissionStarted).state

        val (failed, effects) = m.reduce(state, AlarmEvent.MissionFailed)

        assertEquals(AlarmPhase.MISSION_IN_PROGRESS, failed.phase)
        assertEquals(1, failed.missionFailures)
        assertFalse(effects.contains(AlarmEffect.StopRinging), "failure must not stop the alarm")
    }

    @Test
    fun `snoozing follows the shortening ladder and then runs out`() {
        val m = machine(maxSnoozes = 3)
        var state = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state

        val durations = mutableListOf<Int>()
        repeat(3) {
            val transition = m.reduce(state, AlarmEvent.SnoozeRequested)
            durations += transition.effects
                .filterIsInstance<AlarmEffect.ScheduleSnooze>()
                .single().minutes
            state = m.reduce(transition.state, AlarmEvent.SnoozeExpired).state
        }

        assertEquals(listOf(9, 5, 3), durations)

        val exhausted = m.reduce(state, AlarmEvent.SnoozeRequested)
        assertTrue(exhausted.effects.none { it is AlarmEffect.ScheduleSnooze })
        assertTrue(exhausted.effects.any { it is AlarmEffect.Disclose })
        assertEquals(AlarmPhase.RINGING, exhausted.state.phase, "the alarm keeps ringing")
    }

    @Test
    fun `a watchdog escalates the audio and explains itself`() {
        val m = machine()
        val (state, effects) = m.reduce(
            AlarmState(),
            AlarmEvent.Fired(occurrenceKey = "$key#wd2", watchdogIndex = 2)
        )

        assertEquals(AlarmPhase.RINGING, state.phase)
        assertEquals(2, state.watchdogIndex)
        assertTrue(effects.contains(AlarmEffect.StartRinging(escalate = true)))
        assertTrue(effects.any { it is AlarmEffect.Disclose })
    }

    @Test
    fun `a watchdog for an already-completed alarm stays silent`() {
        val m = machine()
        val completed = AlarmState(phase = AlarmPhase.COMPLETED, outcome = AlarmOutcome.COMPLETED)

        val (state, effects) = m.reduce(
            completed,
            AlarmEvent.Fired(occurrenceKey = "$key#wd1", watchdogIndex = 1)
        )

        assertEquals(AlarmPhase.COMPLETED, state.phase)
        assertEquals(listOf(AlarmEffect.CancelGuardChain), effects)
    }

    @Test
    fun `an unanswered alarm ends as missed`() {
        val m = machine()
        val pending = AlarmState(phase = AlarmPhase.GUARD_PENDING, occurrenceKey = key)

        val (state, effects) = m.reduce(pending, AlarmEvent.GuardWindowElapsed)

        assertEquals(AlarmPhase.MISSED, state.phase)
        assertEquals(AlarmOutcome.MISSED, state.outcome)
        assertTrue(effects.contains(AlarmEffect.RecordOutcome(AlarmOutcome.MISSED)))
        assertTrue(effects.contains(AlarmEffect.CancelGuardChain))
    }

    @Test
    fun `the guard window elapsing after completion changes nothing`() {
        val m = machine()
        val completed = AlarmState(phase = AlarmPhase.COMPLETED, outcome = AlarmOutcome.COMPLETED)

        val (state, effects) = m.reduce(completed, AlarmEvent.GuardWindowElapsed)

        assertEquals(completed, state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `mission attempts are counted`() {
        val m = machine()
        var state = m.reduce(AlarmState(), AlarmEvent.Fired(key)).state
        state = m.reduce(state, AlarmEvent.MissionStarted).state
        state = m.reduce(state, AlarmEvent.MissionFailed).state
        state = m.reduce(state, AlarmEvent.MissionStarted).state

        assertEquals(2, state.missionAttempts)
        assertEquals(1, state.missionFailures)
    }

    @Test
    fun `every wake lock acquisition is matched by a release`() {
        val m = machine()
        val transitions = listOf(
            m.reduce(AlarmState(), AlarmEvent.Fired(key)),
            m.reduce(AlarmState(phase = AlarmPhase.RINGING), AlarmEvent.MissionCompleted),
            m.reduce(AlarmState(phase = AlarmPhase.RINGING), AlarmEvent.SnoozeRequested),
            m.reduce(AlarmState(phase = AlarmPhase.RINGING), AlarmEvent.DismissedWithoutMission),
            m.reduce(AlarmState(phase = AlarmPhase.GUARD_PENDING), AlarmEvent.GuardWindowElapsed)
        )

        val acquires = transitions.sumOf { t ->
            t.effects.count { it == AlarmEffect.AcquireWakeLock }
        }
        val releases = transitions.sumOf { t ->
            t.effects.count { it == AlarmEffect.ReleaseWakeLock }
        }

        // One acquire (the fire) and four terminal paths that each release.
        assertEquals(1, acquires)
        assertEquals(4, releases)
    }
}
