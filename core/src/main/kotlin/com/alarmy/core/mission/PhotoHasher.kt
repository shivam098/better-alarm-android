package com.alarmy.core.mission

import kotlinx.serialization.Serializable

/**
 * Perceptual hashing for the Photo mission.
 *
 * The Photo mission asks the user to register a place when they set the alarm
 * -- the bathroom sink, the kettle, a poster in the hall -- and then to
 * photograph that same place to dismiss it. Getting out of bed and walking
 * there is the point; the photograph is only evidence.
 *
 * That means the comparison must be tolerant of everything that legitimately
 * changes between the two shots (lighting, angle, distance, camera noise, a
 * different time of day) while still rejecting a photo of somewhere else --
 * including, crucially, a photo of the ceiling above the bed.
 *
 * A **difference hash** does this well and, unlike feature matching, is a few
 * lines of integer arithmetic with no dependencies, so it lives in the core
 * module and is unit-tested rather than being buried in an Android class that
 * can only be checked by hand at 6am.
 *
 * How it works: reduce the image to a small grayscale grid, then record whether
 * each pixel is brighter than the one to its right. That captures the structure
 * of the scene -- edges and gradients -- and discards absolute brightness,
 * which is exactly the property that survives a lighting change.
 */
@Serializable
data class PhotoFingerprint(
    /** One bit per comparison, packed into a Long. 64 bits for a 9x8 grid. */
    val bits: Long,
    /** Grid width used to produce [bits]; stored so future sizes stay comparable. */
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT
) {
    companion object {
        const val DEFAULT_WIDTH = 9
        const val DEFAULT_HEIGHT = 8
    }
}

object PhotoHasher {

    /**
     * Computes a difference hash from a grayscale grid.
     *
     * [grayscale] must contain `width * height` values in row-major order, each
     * in 0..255. The caller does the downscaling, because that is the one part
     * that genuinely needs platform bitmap code.
     */
    fun hash(
        grayscale: IntArray,
        width: Int = PhotoFingerprint.DEFAULT_WIDTH,
        height: Int = PhotoFingerprint.DEFAULT_HEIGHT
    ): PhotoFingerprint {
        require(grayscale.size == width * height) {
            "expected ${width * height} samples, got ${grayscale.size}"
        }
        require((width - 1) * height <= 64) { "hash does not fit in 64 bits" }

        var bits = 0L
        var bit = 0
        for (row in 0 until height) {
            for (column in 0 until width - 1) {
                val left = grayscale[row * width + column]
                val right = grayscale[row * width + column + 1]
                if (left > right) bits = bits or (1L shl bit)
                bit++
            }
        }
        return PhotoFingerprint(bits, width, height)
    }

    /** Number of differing bits. 0 is identical, 64 is maximally different. */
    fun distance(a: PhotoFingerprint, b: PhotoFingerprint): Int {
        if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
        return java.lang.Long.bitCount(a.bits xor b.bits)
    }

    /**
     * Whether two photographs are of the same scene.
     *
     * The default threshold of 12 out of 64 bits was chosen to sit clearly
     * between the two populations: the same scene re-photographed by hand
     * typically differs by under 10 bits, while unrelated scenes differ by
     * around 32 (what you would expect from random bits). Being lenient is the
     * safer error here -- an alarm that will not turn off because the light
     * changed is a far worse failure than one that accepts a slightly loose
     * match, since the user still had to walk to the right room to take it.
     */
    fun matches(
        reference: PhotoFingerprint,
        candidate: PhotoFingerprint,
        threshold: Int = DEFAULT_THRESHOLD
    ): Boolean = distance(reference, candidate) <= threshold

    /**
     * How close the user is, as a fraction, for a progress hint.
     *
     * Deliberately coarse: telling the user their photo scored 0.71 would turn
     * the mission into a guessing game played from bed.
     */
    fun confidence(reference: PhotoFingerprint, candidate: PhotoFingerprint): Double {
        val d = distance(reference, candidate)
        if (d == Int.MAX_VALUE) return 0.0
        val bits = (reference.width - 1) * reference.height
        return (1.0 - d.toDouble() / bits).coerceIn(0.0, 1.0)
    }

    const val DEFAULT_THRESHOLD = 12
}

/**
 * A registered photo target.
 *
 * [label] is what the user is shown while the alarm is ringing ("the kitchen
 * kettle"), because a thumbnail alone is hard to interpret half asleep.
 */
@Serializable
data class PhotoTarget(
    val id: String,
    val label: String,
    val fingerprint: PhotoFingerprint
)

/** A registered barcode or QR target. */
@Serializable
data class CodeTarget(
    val id: String,
    val label: String,
    /** The decoded contents, compared exactly. */
    val payload: String
) {
    fun check(scanned: String): MissionAttemptResult =
        if (scanned.trim() == payload.trim()) {
            MissionAttemptResult.Correct
        } else {
            MissionAttemptResult.Incorrect(MissionFailureReason.WRONG_TARGET)
        }
}

/** Photo mission challenge, resolved against a registered target. */
data class PhotoChallenge(
    val target: PhotoTarget,
    val threshold: Int = PhotoHasher.DEFAULT_THRESHOLD
) : MissionChallenge {

    override val fingerprint: String get() = "photo:${target.id}"
    override val prompt: String get() = "Go to ${target.label} and take a photo"

    fun check(candidate: PhotoFingerprint): MissionAttemptResult =
        if (PhotoHasher.matches(target.fingerprint, candidate, threshold)) {
            MissionAttemptResult.Correct
        } else {
            MissionAttemptResult.Incorrect(MissionFailureReason.WRONG_TARGET)
        }

    fun confidence(candidate: PhotoFingerprint): Double =
        PhotoHasher.confidence(target.fingerprint, candidate)
}

/** Barcode / QR mission challenge. */
data class CodeChallenge(val target: CodeTarget) : MissionChallenge {
    override val fingerprint: String get() = "code:${target.id}"
    override val prompt: String get() = "Scan the code on ${target.label}"
    fun check(scanned: String): MissionAttemptResult = target.check(scanned)
}
