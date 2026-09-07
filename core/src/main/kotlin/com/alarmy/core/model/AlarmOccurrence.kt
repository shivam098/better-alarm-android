package com.alarmy.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The three outcomes the product must keep distinct.
 *
 * Conflating [DISMISSED_UNVERIFIED] with [COMPLETED] is the single most common
 * failure of alarm apps: it lets a half-asleep user believe they got up.
 */
@Serializable
enum class AlarmOutcome {
    /** The mission was genuinely completed. The only success state. */
    COMPLETED,

    /** The alarm was silenced without the mission being completed. */
    DISMISSED_UNVERIFIED,

    /** The user snoozed. Explicitly *not* a completion. */
    SNOOZED,

    /** The alarm was never successfully handled. */
    MISSED;

    val isSuccess: Boolean get() = this == COMPLETED

    val displayName: String
        get() = when (this) {
            COMPLETED -> "Completed"
            DISMISSED_UNVERIFIED -> "Dismissed without mission"
            SNOOZED -> "Snoozed"
            MISSED -> "Missed"
        }
}

/** Why the alarm tone may not have been audible. */
@Serializable
enum class AudioSuppressionReason {
    NONE, PHONE_CALL, AUDIO_ROUTE, DO_NOT_DISTURB, VOLUME_ZERO;

    val userExplanation: String?
        get() = when (this) {
            NONE -> null
            PHONE_CALL -> "Your alarm was quietened by a phone call."
            AUDIO_ROUTE -> "Your alarm played through a connected audio device."
            DO_NOT_DISTURB -> "Do Not Disturb was blocking alarms."
            VOLUME_ZERO -> "Your alarm volume was turned all the way down."
        }
}

/**
 * Which delivery mechanism served this alarm.
 *
 * On Android this is a genuine reliability ladder, not a formality. Tier 1 is
 * the only one that survives Doze; the others exist so the app can tell the
 * user it is operating degraded rather than pretending everything is fine.
 */
@Serializable
enum class DeliveryTier(val rank: Int) {
    /** `AlarmManager.setAlarmClock()`. Exempt from Doze, shows in the status bar. */
    EXACT_ALARM_CLOCK(1),

    /** `setExactAndAllowWhileIdle()`. Used when the alarm-clock slot is unavailable. */
    EXACT_WHILE_IDLE(2),

    /** Inexact `set()`. Can drift by minutes. Never marketed as an alarm. */
    INEXACT(3);

    val isReliable: Boolean get() = this == EXACT_ALARM_CLOCK
}

/**
 * A single firing of an alarm. One row per ring, written durably *before* the
 * UI updates so a crash cannot lose the record.
 */
@Serializable
data class AlarmOccurrence(
    val id: String = java.util.UUID.randomUUID().toString(),
    val alarmId: String,
    /** Idempotency key — see [OccurrenceKey]. */
    val occurrenceKey: String,

    val scheduledAtEpochMillis: Long,
    val firedAtEpochMillis: Long? = null,
    val resolvedAtEpochMillis: Long? = null,

    val outcome: AlarmOutcome? = null,
    val missionType: MissionType,
    val difficulty: MissionDifficulty,
    val attempts: Int = 0,
    val failureReasons: List<String> = emptyList(),
    val snoozeCount: Int = 0,
    val timeToCompleteMs: Long? = null,
    val audioSuppression: AudioSuppressionReason = AudioSuppressionReason.NONE,
    val deliveryTier: DeliveryTier = DeliveryTier.EXACT_ALARM_CLOCK,
    /** Set when the configured mission was swapped out at ring time. */
    val substitutedFrom: MissionType? = null,
    /**
     * How late the alarm actually fired, in seconds.
     *
     * Android-specific and important: unlike iOS, delivery here is best-effort
     * and an OEM power manager can delay an alarm by minutes. Recording the
     * real lateness is what lets the reliability screen say "your alarms have
     * been late 4 times this week" instead of guessing.
     */
    val latenessSeconds: Long = 0
) {
    val isResolved: Boolean get() = outcome != null
    val resolvedAt: Instant? get() = resolvedAtEpochMillis?.let(Instant::ofEpochMilli)
    val scheduledAt: Instant get() = Instant.ofEpochMilli(scheduledAtEpochMillis)
}

/**
 * Stable idempotency key for one firing of one alarm.
 *
 * Any reconciliation pass, time-zone change, DST shift or reboot re-arm that
 * produces the same key is a no-op. On Android this matters more than on iOS,
 * because `BOOT_COMPLETED` forces a full re-arm on every restart and a naive
 * implementation would duplicate every pending alarm each time the phone
 * rebooted.
 */
object OccurrenceKey {

    fun make(alarmId: String, fireDate: Instant, zone: ZoneId): String {
        val z: ZonedDateTime = fireDate.atZone(zone)
        return "%s-%04d%02d%02d-%02d%02d".format(
            alarmId, z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute
        )
    }

    /**
     * Watchdogs and snoozes hang off the parent key, so the whole chain can be
     * cancelled atomically the moment the mission is completed.
     */
    fun watchdog(parent: String, index: Int): String = "$parent#wd$index"

    fun snooze(parent: String, round: Int): String = "$parent#snooze$round"

    /** The parent key of any chain member. Splits on the first `#`. */
    fun rootOf(key: String): String = key.substringBefore('#')

    /**
     * A stable positive Int derived from the key, for use as an Android
     * `PendingIntent` request code.
     *
     * `PendingIntent` identity is (requestCode, Intent) — it has no room for a
     * UUID — so the request code *is* the alarm's identity as far as the
     * platform is concerned. Deriving it from the occurrence key rather than
     * counting upwards is what makes cancellation survive a process death:
     * after a reboot the app can reconstruct the exact same request code and
     * cancel an alarm it has never seen in this process.
     *
     * FNV-1a, then masked to 31 bits to keep it non-negative.
     */
    fun requestCode(key: String): Int {
        var hash = -0x7ee3623bL and 0xFFFFFFFFL // 0x811C9DC5, FNV-1a 32-bit basis
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return (hash and 0x7FFFFFFFL).toInt()
    }
}
