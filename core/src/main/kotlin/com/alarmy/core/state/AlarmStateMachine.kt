package com.alarmy.core.state

import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.model.MissionConfig
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Snooze durations, which shorten as the user keeps snoozing.
 *
 * A fixed 9-minute snooze is an invitation to press it six times. The ladder
 * makes each snooze less rewarding than the last without ever removing the
 * option outright — the user keeps control, but the path of least resistance
 * bends towards getting up.
 */
class SnoozePolicy(
    val baseMinutes: Int = 9,
    val maxSnoozes: Int = 3,
    /** When true, snoozing requires completing the mission again. */
    val requiresMission: Boolean = false
) {

    /** Duration for the snooze about to be taken, given how many came before. */
    fun durationMinutes(previousSnoozes: Int): Int {
        val ladder = listOf(
            baseMinutes,
            (baseMinutes * 5.0 / 9.0).roundToInt(),
            (baseMinutes * 3.0 / 9.0).roundToInt()
        ).map { max(1, it) }

        return ladder[previousSnoozes.coerceIn(0, ladder.lastIndex)]
    }

    fun isAllowed(previousSnoozes: Int): Boolean = previousSnoozes < maxSnoozes

    fun remaining(previousSnoozes: Int): Int = max(0, maxSnoozes - previousSnoozes)
}

/** Where an alarm currently is in its lifecycle. */
enum class AlarmPhase {
    IDLE,
    RINGING,
    MISSION_IN_PROGRESS,
    SNOOZED,

    /**
     * Ringing stopped without the mission being completed, and the watchdog
     * chain is now the only thing standing between the user and oversleeping.
     */
    GUARD_PENDING,
    COMPLETED,
    MISSED
}

data class AlarmState(
    val phase: AlarmPhase = AlarmPhase.IDLE,
    val occurrenceKey: String? = null,
    val snoozeCount: Int = 0,
    val missionAttempts: Int = 0,
    val missionFailures: Int = 0,
    /** Chain position, 0 for the original alarm. */
    val watchdogIndex: Int = 0,
    val outcome: AlarmOutcome? = null
)

/**
 * Things that happen to an alarm.
 *
 * [SurfaceLost] and [DismissedWithoutMission] are the Android-specific ones.
 * iOS has a system-drawn Stop button that cannot be removed; Android has no
 * such button, but it does have a dozen ways for the ringing UI to disappear —
 * the notification is swiped, "Clear all" is tapped, the activity is killed
 * under memory pressure, the user switches apps, the power button is pressed.
 * All of them collapse into these two events so the reducer treats them
 * identically: the mission was not completed, so the guard chain stands.
 */
sealed interface AlarmEvent {
    data class Fired(val occurrenceKey: String, val watchdogIndex: Int = 0) : AlarmEvent
    data object MissionStarted : AlarmEvent
    data object MissionCompleted : AlarmEvent
    data object MissionFailed : AlarmEvent
    data object SnoozeRequested : AlarmEvent
    data object SnoozeExpired : AlarmEvent

    /** Ringing ended with no mission completed — swipe, clear-all, task removal. */
    data object DismissedWithoutMission : AlarmEvent

    /** The ringing surface vanished without an explicit user action. */
    data object SurfaceLost : AlarmEvent

    /** The guard window elapsed with the mission never completed. */
    data object GuardWindowElapsed : AlarmEvent
}

/** Side effects the platform layer must perform. Pure data, no Android types. */
sealed interface AlarmEffect {
    data class StartRinging(val escalate: Boolean) : AlarmEffect
    data object StopRinging : AlarmEffect
    data class PresentMission(val mission: MissionConfig) : AlarmEffect
    data object DismissMission : AlarmEffect

    /** Commit the pre-computed watchdog chain to `AlarmManager`. */
    data object ScheduleGuardChain : AlarmEffect
    data object CancelGuardChain : AlarmEffect
    data class ScheduleSnooze(val minutes: Int) : AlarmEffect
    data class RecordOutcome(val outcome: AlarmOutcome) : AlarmEffect

    /** Tell the user plainly what happened. Never fail silently. */
    data class Disclose(val message: String) : AlarmEffect
    data object AcquireWakeLock : AlarmEffect
    data object ReleaseWakeLock : AlarmEffect
}

data class Transition(val state: AlarmState, val effects: List<AlarmEffect>)

/**
 * A pure reducer over alarm lifecycle events.
 *
 * Keeping this free of Android types is what makes the hardest part of the app
 * — the bit that runs at 6am on a device we cannot attach a debugger to —
 * testable on an ordinary JVM.
 */
class AlarmStateMachine(
    private val snoozePolicy: SnoozePolicy = SnoozePolicy(),
    private val guarded: Boolean = true,
    private val mission: MissionConfig = MissionConfig.DEFAULT
) {

    fun reduce(state: AlarmState, event: AlarmEvent): Transition = when (event) {
        is AlarmEvent.Fired -> onFired(state, event)
        AlarmEvent.MissionStarted -> onMissionStarted(state)
        AlarmEvent.MissionCompleted -> onMissionCompleted(state)
        AlarmEvent.MissionFailed -> onMissionFailed(state)
        AlarmEvent.SnoozeRequested -> onSnoozeRequested(state)
        AlarmEvent.SnoozeExpired -> onSnoozeExpired(state)
        AlarmEvent.DismissedWithoutMission -> onDismissedWithoutMission(state)
        AlarmEvent.SurfaceLost -> onSurfaceLost(state)
        AlarmEvent.GuardWindowElapsed -> onGuardWindowElapsed(state)
    }

    private fun onFired(state: AlarmState, event: AlarmEvent.Fired): Transition {
        // A watchdog for an alarm the user already finished must stay silent.
        if (state.phase == AlarmPhase.COMPLETED && event.watchdogIndex > 0) {
            return Transition(state, listOf(AlarmEffect.CancelGuardChain))
        }

        val next = state.copy(
            phase = AlarmPhase.RINGING,
            occurrenceKey = event.occurrenceKey,
            watchdogIndex = event.watchdogIndex,
            outcome = null
        )

        val effects = mutableListOf<AlarmEffect>(
            AlarmEffect.AcquireWakeLock,
            AlarmEffect.StartRinging(escalate = event.watchdogIndex > 0)
        )
        if (guarded) effects += AlarmEffect.ScheduleGuardChain
        if (event.watchdogIndex > 0) {
            effects += AlarmEffect.Disclose(
                "Your alarm is ringing again because the mission was not completed."
            )
        }
        return Transition(next, effects)
    }

    private fun onMissionStarted(state: AlarmState) = Transition(
        state.copy(
            phase = AlarmPhase.MISSION_IN_PROGRESS,
            missionAttempts = state.missionAttempts + 1
        ),
        listOf(AlarmEffect.PresentMission(mission))
    )

    private fun onMissionCompleted(state: AlarmState): Transition {
        val outcome = AlarmOutcome.COMPLETED
        return Transition(
            state.copy(phase = AlarmPhase.COMPLETED, outcome = outcome),
            listOf(
                AlarmEffect.StopRinging,
                AlarmEffect.DismissMission,
                AlarmEffect.CancelGuardChain,
                AlarmEffect.RecordOutcome(outcome),
                AlarmEffect.ReleaseWakeLock
            )
        )
    }

    private fun onMissionFailed(state: AlarmState) = Transition(
        state.copy(
            phase = AlarmPhase.MISSION_IN_PROGRESS,
            missionFailures = state.missionFailures + 1
        ),
        // Ringing deliberately continues through a failure.
        listOf(AlarmEffect.PresentMission(mission))
    )

    private fun onSnoozeRequested(state: AlarmState): Transition {
        if (!snoozePolicy.isAllowed(state.snoozeCount)) {
            return Transition(
                state,
                listOf(
                    AlarmEffect.Disclose(
                        "No snoozes left — complete the mission to stop the alarm."
                    )
                )
            )
        }

        val minutes = snoozePolicy.durationMinutes(state.snoozeCount)
        return Transition(
            state.copy(phase = AlarmPhase.SNOOZED, snoozeCount = state.snoozeCount + 1),
            listOf(
                AlarmEffect.StopRinging,
                AlarmEffect.DismissMission,
                AlarmEffect.ScheduleSnooze(minutes),
                AlarmEffect.Disclose(
                    "Snoozed for $minutes minute${if (minutes == 1) "" else "s"}. " +
                        "${snoozePolicy.remaining(state.snoozeCount + 1)} left."
                ),
                AlarmEffect.ReleaseWakeLock
            )
        )
    }

    private fun onSnoozeExpired(state: AlarmState) = Transition(
        state.copy(phase = AlarmPhase.RINGING),
        listOf(
            AlarmEffect.AcquireWakeLock,
            AlarmEffect.StartRinging(escalate = state.snoozeCount >= 2)
        )
    )

    private fun onDismissedWithoutMission(state: AlarmState): Transition {
        if (!guarded) {
            val outcome = AlarmOutcome.DISMISSED_UNVERIFIED
            return Transition(
                state.copy(phase = AlarmPhase.COMPLETED, outcome = outcome),
                listOf(
                    AlarmEffect.StopRinging,
                    AlarmEffect.RecordOutcome(outcome),
                    AlarmEffect.ReleaseWakeLock
                )
            )
        }

        return Transition(
            state.copy(phase = AlarmPhase.GUARD_PENDING, outcome = AlarmOutcome.DISMISSED_UNVERIFIED),
            listOf(
                AlarmEffect.StopRinging,
                AlarmEffect.DismissMission,
                // The chain was already committed at fire time; this re-asserts
                // it in case the dismissal came from a process about to die.
                AlarmEffect.ScheduleGuardChain,
                AlarmEffect.Disclose(
                    "Mission not completed — your alarm will ring again shortly."
                ),
                AlarmEffect.ReleaseWakeLock
            )
        )
    }

    private fun onSurfaceLost(state: AlarmState): Transition =
        // Losing the UI is not consent to stop. Identical handling to an
        // explicit dismissal, which is the point.
        onDismissedWithoutMission(state)

    private fun onGuardWindowElapsed(state: AlarmState): Transition {
        if (state.phase == AlarmPhase.COMPLETED) return Transition(state, emptyList())

        val outcome = AlarmOutcome.MISSED
        return Transition(
            state.copy(phase = AlarmPhase.MISSED, outcome = outcome),
            listOf(
                AlarmEffect.StopRinging,
                AlarmEffect.CancelGuardChain,
                AlarmEffect.RecordOutcome(outcome),
                AlarmEffect.Disclose("Your alarm went unanswered."),
                AlarmEffect.ReleaseWakeLock
            )
        )
    }
}
