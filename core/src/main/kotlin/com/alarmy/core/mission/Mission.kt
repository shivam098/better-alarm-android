package com.alarmy.core.mission

import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionType

/** How far through a mission the user is. */
data class MissionProgress(
    val completedSteps: Int,
    val totalSteps: Int,
    val currentStepAttempts: Int = 0
) {
    val fraction: Double
        get() = if (totalSteps == 0) 0.0 else completedSteps.toDouble() / totalSteps

    val isComplete: Boolean get() = completedSteps >= totalSteps
}

/** Why an attempt was rejected. Every case is explainable to the user. */
enum class MissionFailureReason {
    INCORRECT_ANSWER,
    TIMED_OUT,

    /** Sensor data matched a machine, not a person. */
    IMPLAUSIBLE_MOTION,

    /** The required sensor or permission is unavailable. */
    CAPABILITY_UNAVAILABLE,

    /**
     * A camera mission photographed or scanned the wrong thing.
     *
     * Kept separate from [INCORRECT_ANSWER] because the remedy is completely
     * different: the user has not made a mistake, they are in the wrong room.
     */
    WRONG_TARGET,
    ABANDONED;

    val userMessage: String
        get() = when (this) {
            INCORRECT_ANSWER -> "Not quite — try again."
            TIMED_OUT -> "Out of time. Here's a new one."
            IMPLAUSIBLE_MOTION ->
                "That didn't look like real movement. Try again with a full, deliberate motion."
            CAPABILITY_UNAVAILABLE ->
                "This mission isn't available on your device, so we've switched to another one."
            WRONG_TARGET -> "That isn't the right one. Head to the place you set up."
            ABANDONED -> "Mission not completed."
        }
}

/** A single unit of work within a mission. */
interface MissionChallenge {
    /** Stable identity used to avoid repeating recent challenges. */
    val fingerprint: String

    /** What the user is asked to do. */
    val prompt: String
}

sealed interface MissionAttemptResult {
    data object Correct : MissionAttemptResult
    data class Incorrect(val reason: MissionFailureReason) : MissionAttemptResult
}

/**
 * Human-facing description of a mission type, used by the picker.
 *
 * Kept in the core rather than the UI layer so the iOS and Android builds
 * describe the same mission in the same words.
 */
data class MissionDescriptor(
    val type: MissionType,
    val summary: String,
    val howItWorks: String
) {
    val title: String get() = type.displayName
}

object MissionLibrary {

    val descriptors: List<MissionDescriptor> = listOf(
        MissionDescriptor(
            type = MissionType.NONE,
            summary = "A plain alarm with a slide-to-stop control.",
            howItWorks = "Slide to stop. No verification is required."
        ),
        MissionDescriptor(
            type = MissionType.MATH,
            summary = "Solve arithmetic problems.",
            howItWorks =
                "Solve a short series of problems. Difficulty controls the size of the " +
                    "numbers and how many operations are involved."
        ),
        MissionDescriptor(
            type = MissionType.MISSING_SYMBOL,
            summary = "Find the symbol missing from an ordered set.",
            howItWorks =
                "A row of symbols appears with exactly one absent. Pick the missing one " +
                    "from the options offered."
        ),
        MissionDescriptor(
            type = MissionType.MEMORY,
            summary = "Repeat a sequence from memory.",
            howItWorks =
                "Watch a sequence light up, then tap it back in order. The sequence gets " +
                    "longer as difficulty rises."
        ),
        MissionDescriptor(
            type = MissionType.TYPING,
            summary = "Type a phrase exactly.",
            howItWorks =
                "Retype the phrase shown, including punctuation and capitals."
        ),
        MissionDescriptor(
            type = MissionType.SHAKE,
            summary = "Shake your phone.",
            howItWorks =
                "Shake until the counter fills. Movement is checked for natural variation, " +
                    "so a fan or a desk drawer won't do it for you."
        ),
        MissionDescriptor(
            type = MissionType.STEPS,
            summary = "Walk a set number of steps.",
            howItWorks =
                "Get out of bed and walk. Steps are counted with a cadence check, so " +
                    "shaking the phone in bed does not count."
        ),
        MissionDescriptor(
            type = MissionType.SQUAT,
            summary = "Complete squat repetitions.",
            howItWorks =
                "Hold the phone and squat. Each repetition needs a full down-and-up " +
                    "movement at a human pace."
        ),
        MissionDescriptor(
            type = MissionType.PHOTO,
            summary = "Retake a photo of a place you registered.",
            howItWorks =
                "Register a photo of a spot in your home — the kettle, the bathroom " +
                    "mirror — then match it in the morning. Having to walk there is the point."
        ),
        MissionDescriptor(
            type = MissionType.QR,
            summary = "Scan a barcode or QR code you registered in advance.",
            howItWorks =
                "Register any barcode or QR code, keep it out of reach of the bed, and " +
                    "scan it to stop the alarm."
        ),
        MissionDescriptor(
            type = MissionType.COMBINATION,
            summary = "Chain several missions together.",
            howItWorks =
                "Complete two to five missions back to back. Useful when a single mission " +
                    "has stopped waking you up."
        )
    )

    fun descriptor(type: MissionType): MissionDescriptor =
        descriptors.first { it.type == type }

    /** Missions available given what the device and permissions actually allow. */
    fun available(
        hasAccelerometer: Boolean = true,
        hasStepCounter: Boolean = true,
        hasCamera: Boolean = true,
        activityRecognitionGranted: Boolean = true,
        cameraGranted: Boolean = true
    ): List<MissionType> = MissionType.entries.filter { type ->
        when (type) {
            MissionType.SHAKE -> hasAccelerometer
            MissionType.SQUAT -> hasAccelerometer
            MissionType.STEPS -> hasStepCounter && activityRecognitionGranted
            MissionType.PHOTO, MissionType.QR -> hasCamera && cameraGranted
            else -> true
        }
    }

    /**
     * Picks a usable mission, walking the fallback chain when the configured one
     * is not available.
     *
     * A mission that cannot run must never mean an alarm that cannot be stopped,
     * which is why every chain terminates in [MissionType.MATH].
     */
    fun resolve(config: MissionConfig, available: List<MissionType>): MissionConfig {
        if (config.type == MissionType.COMBINATION) {
            return config.copy(children = config.children.map { resolve(it, available) })
        }
        if (config.type in available) return config

        val replacement = config.type.fallbackChain.firstOrNull { it in available }
            ?: MissionType.MATH

        return config.copy(
            type = replacement,
            targetCount = MissionConfig.defaultTargetCount(replacement)
        )
    }
}
