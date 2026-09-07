package com.alarmy.core.schedule

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * What to do about an alarm whose time passed while the device was off,
 * restarting, or otherwise unavailable.
 *
 * This carries more weight on Android than on iOS. iOS alarms survive a reboot
 * by themselves; Android's do not — `AlarmManager` forgets everything when the
 * device restarts, so every pending alarm has to be rebuilt from storage by the
 * `BOOT_COMPLETED` receiver. If the phone was off across the alarm time, this
 * policy is what decides whether the user is woken late or merely told.
 */
@Serializable
enum class MissedWindowRule {
    /** Fire immediately if still within the grace period; otherwise skip. Default. */
    GRACE_PERIOD,
    ALWAYS_SKIP,
    ALWAYS_TRIGGER;

    val displayName: String
        get() = when (this) {
            GRACE_PERIOD -> "Ring if recent"
            ALWAYS_SKIP -> "Skip"
            ALWAYS_TRIGGER -> "Always ring"
        }

    val explanation: String
        get() = when (this) {
            GRACE_PERIOD ->
                "If your phone was off, the alarm rings as soon as it comes back — " +
                    "but only if it missed by less than the grace period."
            ALWAYS_SKIP -> "A missed alarm is skipped and recorded in your history."
            ALWAYS_TRIGGER ->
                "A missed alarm rings as soon as your phone is available, however late."
        }
}

sealed interface MissedWindowDecision {
    data class TriggerNow(val lateBy: Duration) : MissedWindowDecision
    data class SkipAndRecordMissed(val lateBy: Duration) : MissedWindowDecision
    data object NotYetDue : MissedWindowDecision
}

class MissedWindowPolicy(
    val rule: MissedWindowRule = MissedWindowRule.GRACE_PERIOD,
    val graceMinutes: Int = 15
) {

    fun decide(scheduledAt: Instant, now: Instant): MissedWindowDecision {
        val lateBy = Duration.between(scheduledAt, now)
        if (lateBy.isNegative || lateBy.isZero) return MissedWindowDecision.NotYetDue

        return when (rule) {
            MissedWindowRule.ALWAYS_TRIGGER -> MissedWindowDecision.TriggerNow(lateBy)
            MissedWindowRule.ALWAYS_SKIP -> MissedWindowDecision.SkipAndRecordMissed(lateBy)
            MissedWindowRule.GRACE_PERIOD ->
                if (lateBy <= Duration.ofMinutes(graceMinutes.toLong())) {
                    MissedWindowDecision.TriggerNow(lateBy)
                } else {
                    MissedWindowDecision.SkipAndRecordMissed(lateBy)
                }
        }
    }

    /** User-facing sentence explaining what just happened. Never silent. */
    fun disclosure(decision: MissedWindowDecision, alarmName: String): String? =
        when (decision) {
            is MissedWindowDecision.NotYetDue -> null
            is MissedWindowDecision.TriggerNow ->
                "\"$alarmName\" is ringing ${humanDuration(decision.lateBy)} late — " +
                    "your phone was unavailable."
            is MissedWindowDecision.SkipAndRecordMissed ->
                "\"$alarmName\" was missed by ${humanDuration(decision.lateBy)} — " +
                    "your phone was off."
        }

    companion object {
        fun humanDuration(duration: Duration): String {
            val minutes = duration.toMinutes()
            return when {
                minutes < 1 -> "less than a minute"
                minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"}"
                else -> {
                    val hours = minutes / 60
                    val remainder = minutes % 60
                    if (remainder == 0L) "$hours hour${if (hours == 1L) "" else "s"}"
                    else "${hours}h ${remainder}m"
                }
            }
        }
    }
}
