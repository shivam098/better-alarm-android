package com.alarmy.core.model

import kotlinx.serialization.Serializable

/**
 * The full mission library. Every entry is free for every user: there is no
 * premium tier and no locked mission.
 */
@Serializable
enum class MissionType {
    NONE, MATH, MISSING_SYMBOL, MEMORY, TYPING, SHAKE, STEPS, SQUAT, PHOTO, QR, COMBINATION;

    val displayName: String
        get() = when (this) {
            NONE -> "Slide to stop"
            MATH -> "Math"
            MISSING_SYMBOL -> "Find the Missing Symbol"
            MEMORY -> "Memory"
            TYPING -> "Typing"
            SHAKE -> "Shake"
            STEPS -> "Walking"
            SQUAT -> "Squats"
            PHOTO -> "Photo"
            QR -> "Barcode / QR"
            COMBINATION -> "Combination"
        }

    val category: MissionCategory
        get() = when (this) {
            NONE -> MissionCategory.NONE
            MATH, MEMORY, TYPING -> MissionCategory.MENTAL
            MISSING_SYMBOL -> MissionCategory.VISUAL
            SHAKE, STEPS, SQUAT -> MissionCategory.PHYSICAL
            PHOTO, QR -> MissionCategory.LOCATION
            COMBINATION -> MissionCategory.MIXED
        }

    /**
     * Runtime permissions this mission needs.
     *
     * Note what is *not* here: scheduling the alarm itself. On Android that is
     * a manifest-level concern ([android.permission.USE_EXACT_ALARM]) plus a
     * pile of OEM battery settings, which is a different problem handled by the
     * reliability layer rather than by a mission.
     */
    val requiredPermissions: Set<MissionPermission>
        get() = when (this) {
            NONE, MATH, MISSING_SYMBOL, MEMORY, TYPING, COMBINATION -> emptySet()
            SHAKE -> setOf(MissionPermission.MOTION)
            STEPS -> setOf(MissionPermission.ACTIVITY_RECOGNITION)
            SQUAT -> setOf(MissionPermission.CAMERA, MissionPermission.MOTION)
            PHOTO, QR -> setOf(MissionPermission.CAMERA)
        }

    val isPhysical: Boolean get() = this == SHAKE || this == STEPS || this == SQUAT

    /**
     * Ordered substitutes offered when a permission or sensor is unavailable.
     * Every chain terminates in a mission needing no permissions at all, so an
     * alarm can never become uncompletable.
     */
    val fallbackChain: List<MissionType>
        get() = when (this) {
            PHOTO -> listOf(QR, TYPING, MATH)
            QR -> listOf(TYPING, MATH)
            SQUAT -> listOf(STEPS, SHAKE, MATH)
            STEPS -> listOf(SHAKE, MATH)
            SHAKE -> listOf(MATH)
            else -> listOf(MATH)
        }

    /** Base hardness weight used when merging overlapping alarms. */
    internal val baseHardness: Int
        get() = when (this) {
            NONE -> 0
            MATH, MISSING_SYMBOL -> 2
            MEMORY, TYPING, SHAKE -> 3
            STEPS -> 4
            SQUAT, PHOTO, QR -> 5
            COMBINATION -> 6
        }
}

@Serializable
enum class MissionCategory {
    NONE, MENTAL, VISUAL, PHYSICAL, LOCATION, MIXED;

    val displayName: String
        get() = when (this) {
            NONE -> "None"
            MENTAL -> "Mental"
            VISUAL -> "Visual"
            PHYSICAL -> "Physical"
            LOCATION -> "Location"
            MIXED -> "Mixed"
        }
}

/**
 * Runtime permissions a mission may need.
 *
 * These map onto real Android permissions:
 *  - [CAMERA] -> `android.permission.CAMERA`
 *  - [ACTIVITY_RECOGNITION] -> `android.permission.ACTIVITY_RECOGNITION`
 *  - [MOTION] -> no permission at all; accelerometer and gyroscope are free to
 *    read on Android. It is modelled anyway so a device that genuinely lacks
 *    the sensor is handled by the same fallback path as a denied permission.
 */
@Serializable
enum class MissionPermission {
    CAMERA, MOTION, ACTIVITY_RECOGNITION;

    val displayName: String
        get() = when (this) {
            CAMERA -> "Camera"
            MOTION -> "Motion sensors"
            ACTIVITY_RECOGNITION -> "Physical activity"
        }

    /** Plain-language reason shown *before* the system prompt. */
    val rationale: String
        get() = when (this) {
            CAMERA -> "Used only while you are completing a camera mission. " +
                "Frames are analysed on your device and never uploaded."
            MOTION -> "Used only while a motion mission is running, to confirm the movement really happened."
            ACTIVITY_RECOGNITION -> "Used only while a walking mission is running, to count your steps."
        }
}

@Serializable
enum class MissionDifficulty(val level: Int) {
    VERY_EASY(1), EASY(2), MEDIUM(3), HARD(4), VERY_HARD(5);

    val displayName: String
        get() = when (this) {
            VERY_EASY -> "Very easy"
            EASY -> "Easy"
            MEDIUM -> "Medium"
            HARD -> "Hard"
            VERY_HARD -> "Very hard"
        }

    /** One step harder, capped. Used by Mission Guard escalation. */
    fun harder(): MissionDifficulty = entries.firstOrNull { it.level == level + 1 } ?: this
}

/**
 * Per-alarm mission settings.
 *
 * [children] is only populated for [MissionType.COMBINATION], which chains 2–5
 * missions completed in order.
 */
@Serializable
data class MissionConfig(
    val type: MissionType = MissionType.MATH,
    val difficulty: MissionDifficulty = MissionDifficulty.MEDIUM,
    val targetCount: Int = defaultTargetCount(MissionType.MATH),
    val referenceAssetIds: List<String> = emptyList(),
    val children: List<MissionConfig> = emptyList()
) {
    val requiredPermissions: Set<MissionPermission>
        get() = if (type == MissionType.COMBINATION) {
            children.flatMap { it.requiredPermissions }.toSet()
        } else {
            type.requiredPermissions
        }

    /** Score used to pick the hardest mission when alarms are merged. */
    val hardnessScore: Int
        get() = if (type == MissionType.COMBINATION) {
            type.baseHardness + children.sumOf { it.hardnessScore }
        } else {
            type.baseHardness * 10 + difficulty.level
        }

    /** A copy escalated one difficulty step. */
    fun escalated(): MissionConfig = copy(
        difficulty = difficulty.harder(),
        children = children.map { it.escalated() }
    )

    /** Clamps [targetCount] into the legal range for the current type. */
    fun normalized(): MissionConfig {
        val range = targetRange(type)
        return copy(
            targetCount = targetCount.coerceIn(range),
            children = if (type == MissionType.COMBINATION) children else emptyList()
        )
    }

    companion object {
        val DEFAULT = MissionConfig(MissionType.MATH, MissionDifficulty.MEDIUM, 3)

        fun defaultTargetCount(type: MissionType): Int = when (type) {
            MissionType.NONE -> 0
            MissionType.MATH, MissionType.MISSING_SYMBOL, MissionType.MEMORY -> 3
            MissionType.TYPING -> 1
            MissionType.SHAKE, MissionType.STEPS -> 30
            MissionType.SQUAT -> 10
            MissionType.PHOTO, MissionType.QR -> 1
            MissionType.COMBINATION -> 2
        }

        /**
         * Allowed range for [targetCount], enforced by the editor so a user
         * cannot configure something unsafe or uncompletable at 6am.
         */
        fun targetRange(type: MissionType): IntRange = when (type) {
            MissionType.NONE -> 0..0
            MissionType.MATH, MissionType.MISSING_SYMBOL, MissionType.MEMORY -> 1..10
            MissionType.TYPING -> 1..5
            MissionType.SHAKE -> 10..200
            MissionType.STEPS -> 10..500
            MissionType.SQUAT -> 3..50
            MissionType.PHOTO, MissionType.QR -> 1..3
            MissionType.COMBINATION -> 2..5
        }
    }
}
