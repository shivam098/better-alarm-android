package com.alarmy.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** How an alarm's time is interpreted when the device changes time zone. */
@Serializable
enum class ScheduleMode {
    /** "7:00 AM wherever I am" — the default, and the only sensible mode for a repeating alarm. */
    WALL_CLOCK,

    /** "This exact instant" — for a traveller who means a specific moment in time. */
    ABSOLUTE
}

@Serializable
data class SnoozeSettings(
    val isEnabled: Boolean = true,
    /** Minutes. */
    val duration: Int = 9,
    /** `null` means unlimited. */
    val limit: Int? = 3,
    /** Require the mission to be completed *before* granting the snooze. */
    val costsMission: Boolean = false,
    /** Shorten each successive snooze: 9 -> 5 -> 3. */
    val escalating: Boolean = false
) {
    companion object {
        val DEFAULT = SnoozeSettings()
        val ALLOWED_DURATIONS = listOf(1, 3, 5, 9, 10, 15, 20, 30)
    }
}

/**
 * Mission Guard — a chain of follow-up alarms that only a genuine mission
 * completion cancels.
 *
 * ## Why this exists on Android, and how that differs from iOS
 *
 * The iOS build needs Mission Guard because AlarmKit *always* renders a Stop
 * button that a third-party app cannot remove. Android has no such constraint:
 * a full-screen intent hands the ringing UI to us completely, so the only
 * buttons on screen are the ones this app chose to draw, and none of them
 * dismiss an unfinished mission.
 *
 * Mission Guard is still here, because Android has its own escape routes that
 * iOS does not:
 *
 *  * The user swipes the notification away, or hits "Clear all".
 *  * The user presses the power button or volume key to silence the device.
 *  * The user navigates away with the task switcher and leaves it there.
 *  * The system kills the ringing activity under memory pressure.
 *
 * So the mechanism is shared but the threat model is inverted: on iOS the alarm
 * is guaranteed to fire and the danger is the Stop button; on Android we own
 * the whole screen and the danger is that the alarm never fires at all.
 *
 * That second problem is not solvable here — see the reliability layer in the
 * app module, and [DeliveryTier].
 */
@Serializable
data class MissionGuardSettings(
    val isEnabled: Boolean = true,
    /** How long the app will keep bringing the alarm back, in minutes. */
    val windowMinutes: Int = 30,
    /** Raise mission difficulty after repeated evasion. */
    val escalates: Boolean = true
) {
    companion object {
        val DEFAULT = MissionGuardSettings()
        val ALLOWED_WINDOWS = listOf(0, 15, 30, 60)
    }
}

/** A user-configured alarm. This is the persisted entity. */
@Serializable
data class Alarm(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Alarm",
    /** 0..23 in the user's local wall clock. */
    val hour: Int = 7,
    /** 0..59. */
    val minute: Int = 0,
    /** Empty means a one-time alarm. */
    val repeatDays: Set<Weekday> = emptySet(),
    val isEnabled: Boolean = true,
    val scheduleMode: ScheduleMode = ScheduleMode.WALL_CLOCK,
    /** Only meaningful when [scheduleMode] is [ScheduleMode.ABSOLUTE]. Epoch millis. */
    val absoluteEpochMillis: Long? = null,

    val soundId: String = SoundLibrary.DEFAULT_SOUND_ID,
    val volume: Double = 1.0,
    val gentleRamp: Boolean = true,
    val vibrationEnabled: Boolean = true,

    val snooze: SnoozeSettings = SnoozeSettings.DEFAULT,
    val mission: MissionConfig = MissionConfig.DEFAULT,
    /** Explicit substitute if the mission's sensors/permissions are unavailable at ring time. */
    val fallbackMission: MissionType? = null,
    val missionGuard: MissionGuardSettings = MissionGuardSettings.DEFAULT,

    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    val isRepeating: Boolean get() = repeatDays.isNotEmpty()

    val absoluteInstant: Instant? get() = absoluteEpochMillis?.let(Instant::ofEpochMilli)

    /**
     * The effective substitute mission, honouring an explicit choice and
     * otherwise walking the mission's own fallback chain.
     */
    fun resolvedFallback(unavailable: Set<MissionPermission>): MissionType {
        fallbackMission?.let { explicit ->
            if (explicit.requiredPermissions.none { it in unavailable }) return explicit
        }
        return mission.type.fallbackChain
            .firstOrNull { candidate -> candidate.requiredPermissions.none { it in unavailable } }
            ?: MissionType.MATH
    }

    /** `true` when the mission cannot be silently bypassed. */
    val isGuarded: Boolean
        get() = missionGuard.isEnabled &&
            missionGuard.windowMinutes > 0 &&
            mission.type != MissionType.NONE
}

/**
 * Built-in alarm sounds.
 *
 * [resourceName] is a raw-resource name (`res/raw/<name>.ogg`). If the resource
 * is missing the scheduler falls back to the system default alarm tone rather
 * than ringing silently, which is why this repo can ship without audio assets.
 */
object SoundLibrary {
    data class Sound(
        val id: String,
        val displayName: String,
        val resourceName: String,
        val isGentle: Boolean
    )

    const val DEFAULT_SOUND_ID = "radar"

    val all: List<Sound> = listOf(
        Sound("radar", "Radar", "radar", isGentle = false),
        Sound("ascend", "Ascend", "ascend", isGentle = true),
        Sound("sunrise", "Sunrise", "sunrise", isGentle = true),
        Sound("chimes", "Chimes", "chimes", isGentle = true),
        Sound("klaxon", "Klaxon", "klaxon", isGentle = false),
        Sound("siren", "Siren", "siren", isGentle = false),
        Sound("insistent", "Insistent", "insistent", isGentle = false)
    )

    fun soundFor(id: String): Sound = all.firstOrNull { it.id == id } ?: all[0]
}
