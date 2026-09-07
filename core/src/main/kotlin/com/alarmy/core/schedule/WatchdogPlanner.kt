package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.OccurrenceKey
import java.time.Instant

/** A single pre-scheduled follow-up alarm. */
data class WatchdogEntry(
    /** 1-based position in the chain. */
    val index: Int,
    val fireDate: Instant,
    val key: String,
    /** The mission to present, already escalated where applicable. */
    val mission: MissionConfig,
    val alertTitle: String
)

data class WatchdogPlan(
    val parentKey: String,
    val entries: List<WatchdogEntry>
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Builds the Mission Guard watchdog chain.
 *
 * ## Why the chain is pre-scheduled on Android too
 *
 * On iOS pre-scheduling is forced: there is no guaranteed background execution
 * after the user presses Stop, so every follow-up must already be committed to
 * the system before the user falls asleep.
 *
 * Android is friendlier — a foreground service or a `BOOT_COMPLETED` receiver
 * genuinely can re-arm the next alarm reactively. The chain is still
 * pre-scheduled here, for two reasons:
 *
 *  1. **Reactive re-arming assumes the app is alive.** The cases Mission Guard
 *     exists to cover are exactly the cases where it might not be: the task was
 *     swiped away, the activity was killed under memory pressure, an OEM power
 *     manager decided the app had outstayed its welcome.
 *  2. **Committing to `AlarmManager` moves the promise into the system.** A
 *     pending alarm survives our process dying. State in our own heap does not.
 *
 * The one thing pre-scheduling does *not* survive is a force-stop from Settings,
 * which cancels every alarm the app owns. Nothing in this class can fix that;
 * only the boot receiver re-arming afterwards can, which is why the reliability
 * layer treats "force-stopped" as a first-class diagnosis.
 */
class WatchdogPlanner(
    /** Cap on follow-ups per alarm, to stay inside the scheduling budget. */
    private val maxEntries: Int = 6,
    /** Chain position from which the mission escalates one difficulty step. */
    private val escalateFromIndex: Int = 3
) {

    fun plan(alarm: Alarm, parentFireDate: Instant, parentKey: String): WatchdogPlan {
        if (!alarm.isGuarded) return WatchdogPlan(parentKey, emptyList())

        val window = alarm.missionGuard.windowMinutes
        val entries = mutableListOf<WatchdogEntry>()

        for ((position, offset) in OFFSETS_MINUTES.withIndex()) {
            if (offset > window) continue
            if (entries.size >= maxEntries) break

            val index = position + 1
            val fireDate = parentFireDate.plusSeconds(offset * 60L)

            // Escalate only once the user has repeatedly evaded the mission.
            val shouldEscalate = alarm.missionGuard.escalates && index >= escalateFromIndex
            val mission = if (shouldEscalate) alarm.mission.escalated() else alarm.mission

            entries += WatchdogEntry(
                index = index,
                fireDate = fireDate,
                key = OccurrenceKey.watchdog(parentKey, index),
                mission = mission,
                alertTitle = "Mission not completed — ${alarm.name}"
            )
        }

        return WatchdogPlan(parentKey, entries)
    }

    companion object {
        /** Minutes after the parent alarm at which follow-ups fire. */
        val OFFSETS_MINUTES = listOf(1, 3, 6, 10, 15, 20, 25, 30, 40, 50, 60)
    }
}

/**
 * Prioritises pending alarms when there are more to schedule than the budget
 * allows.
 *
 * Android imposes no hard cap on pending `AlarmManager` alarms the way the old
 * 64-notification limit did, but unbounded scheduling is still a bad idea: each
 * pending alarm is a row the system tracks on our behalf, and a user with
 * twenty repeating alarms and a 60-minute guard window would otherwise generate
 * hundreds. The budget keeps that bounded and, more importantly, makes the
 * trimming order explicit rather than arbitrary.
 *
 * Priority: imminent non-watchdogs, then imminent watchdogs, then repeating
 * alarms, then everything else.
 */
class SchedulingBudget(private val capacity: Int = 64) {

    data class Request(
        val key: String,
        val fireDate: Instant,
        val isWatchdog: Boolean,
        val isRepeating: Boolean
    )

    fun prioritise(requests: List<Request>, now: Instant): List<Request> {
        val horizon = now.plusSeconds(24 * 60 * 60)

        fun rank(request: Request): Int {
            val imminent = !request.fireDate.isAfter(horizon)
            return when {
                imminent && !request.isWatchdog -> 0
                imminent && request.isWatchdog -> 1
                request.isRepeating -> 2
                else -> 3
            }
        }

        return requests
            .filter { it.fireDate.isAfter(now) }
            .sortedWith(compareBy({ rank(it) }, { it.fireDate }))
            .take(capacity)
    }
}
