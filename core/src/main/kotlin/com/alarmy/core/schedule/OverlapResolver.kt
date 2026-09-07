package com.alarmy.core.schedule

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.MissionConfig
import java.time.Duration
import java.time.Instant

/** An alarm together with its next computed firing. */
data class PendingAlarm(
    val alarm: Alarm,
    val occurrence: ScheduledOccurrence
)

/** One or more alarms presented to the user as a single wake-up event. */
data class AlarmSession(
    /** The session key is the earliest member's occurrence key. */
    val key: String,
    val fireDate: Instant,
    /** Every alarm folded into this session, earliest first. Never empty. */
    val members: List<PendingAlarm>,
    /** Combined name, e.g. "Gym + Class". */
    val displayName: String,
    /** The hardest mission among the members. */
    val mission: MissionConfig
) {
    val alarmIds: List<String> get() = members.map { it.alarm.id }
    val isMerged: Boolean get() = members.size > 1
}

/**
 * Decides what happens when two alarms land close together.
 *
 * The rule is deliberately deterministic and surfaced in Settings, because an
 * alarm that silently swallows another alarm is a safety problem, not a tidy
 * piece of UX.
 */
class OverlapResolver(
    /** Alarms within this window of each other become one session. */
    private val mergeWindow: Duration = Duration.ofSeconds(90)
) {

    /**
     * Folds close-together alarms into sessions.
     *
     * Merging is anchored on the first alarm of each session rather than being
     * transitive, so a long chain of alarms 80 seconds apart cannot collapse
     * into one unbounded session.
     */
    fun resolve(pending: List<PendingAlarm>): List<AlarmSession> {
        val sorted = pending.sortedBy { it.occurrence.fireDate }
        val sessions = mutableListOf<AlarmSession>()
        var bucket = mutableListOf<PendingAlarm>()

        fun flush() {
            val anchor = bucket.firstOrNull() ?: return
            // The session adopts the earliest time and the hardest mission.
            val hardest = bucket.maxByOrNull { it.alarm.mission.hardnessScore }
            // Every merged alarm's name is shown; none is hidden.
            val name = bucket.joinToString(" + ") { it.alarm.name }

            sessions += AlarmSession(
                key = anchor.occurrence.key,
                fireDate = anchor.occurrence.fireDate,
                members = bucket.toList(),
                displayName = name,
                mission = hardest?.alarm?.mission ?: anchor.alarm.mission
            )
            bucket = mutableListOf()
        }

        for (item in sorted) {
            val anchor = bucket.firstOrNull()
            if (anchor != null) {
                val gap = Duration.between(anchor.occurrence.fireDate, item.occurrence.fireDate)
                if (gap <= mergeWindow) {
                    bucket += item
                    continue
                }
                flush()
            }
            bucket = mutableListOf(item)
        }
        flush()

        return sessions
    }

    /**
     * Sessions further apart than the merge window are strictly sequential — a
     * later session must not ring while an earlier one is still unresolved.
     */
    fun isBlocked(session: AlarmSession, unresolved: List<AlarmSession>): Boolean =
        unresolved.any { it.fireDate.isBefore(session.fireDate) }
}
