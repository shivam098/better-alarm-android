package com.alarmy.core.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.alarmy.core.support.SeededRandom

@DisplayName("PhotoHasher")
class PhotoHasherTest {

    private val width = PhotoFingerprint.DEFAULT_WIDTH
    private val height = PhotoFingerprint.DEFAULT_HEIGHT

    /** A smooth left-to-right gradient: every pixel is darker than its right neighbour. */
    private fun gradient(): IntArray = IntArray(width * height) { index ->
        val column = index % width
        column * 28
    }

    private fun uniform(value: Int) = IntArray(width * height) { value }

    @Test
    @DisplayName("a hash is stable for identical input")
    fun stable() {
        val a = PhotoHasher.hash(gradient())
        val b = PhotoHasher.hash(gradient())
        assertEquals(a, b)
        assertEquals(0, PhotoHasher.distance(a, b))
    }

    @Test
    @DisplayName("a left-to-right gradient sets no bits")
    fun gradientHasNoBits() {
        // Every comparison is "left > right", and in a rising gradient it never is.
        assertEquals(0L, PhotoHasher.hash(gradient()).bits)
    }

    @Test
    @DisplayName("reversing the gradient sets every bit")
    fun reversedGradient() {
        val reversed = IntArray(width * height) { index ->
            val column = index % width
            (width - 1 - column) * 28
        }
        // 8 comparisons per row x 8 rows = exactly 64 bits, all set.
        //
        // Written as -1L rather than (1L shl 64) - 1: JVM shift counts are
        // taken modulo 64, so `1L shl 64` is `1L shl 0` == 1, and the "obvious"
        // expression would silently expect 0.
        val allBitsSet = -1L
        assertEquals(64, (width - 1) * height)
        assertEquals(allBitsSet, PhotoHasher.hash(reversed).bits)
    }

    @Test
    @DisplayName("the hash ignores absolute brightness")
    fun brightnessInvariant() {
        // The core property the Photo mission depends on: the same scene at a
        // different time of day must still match.
        val morning = gradient()
        val evening = IntArray(morning.size) { (morning[it] / 3).coerceAtMost(255) }

        val a = PhotoHasher.hash(morning)
        val b = PhotoHasher.hash(evening)
        assertTrue(
            PhotoHasher.matches(a, b),
            "a uniformly dimmed photo of the same scene must still match"
        )
    }

    @Test
    @DisplayName("a flat image produces no bits, and two flat images match")
    fun flatImages() {
        val a = PhotoHasher.hash(uniform(10))
        val b = PhotoHasher.hash(uniform(200))
        assertEquals(0L, a.bits)
        assertEquals(0L, b.bits)
        // Both are featureless, so they are indistinguishable. This is a real
        // limitation of the technique -- pointing at a blank wall matches any
        // other blank wall -- and the photo registration flow warns about it.
        assertTrue(PhotoHasher.matches(a, b))
    }

    @Test
    @DisplayName("noise tolerance: small perturbations still match")
    fun toleratesNoise() {
        val random = SeededRandom(42)
        val base = IntArray(width * height) { 40 + random.nextInt(180) }
        val noisy = IntArray(base.size) { index ->
            (base[index] + random.nextInt(11) - 5).coerceIn(0, 255)
        }
        val a = PhotoHasher.hash(base)
        val b = PhotoHasher.hash(noisy)
        assertTrue(
            PhotoHasher.distance(a, b) <= PhotoHasher.DEFAULT_THRESHOLD,
            "camera noise should not break a match, distance was ${PhotoHasher.distance(a, b)}"
        )
    }

    @Test
    @DisplayName("unrelated scenes do not match")
    fun rejectsDifferentScenes() {
        // Two independent random scenes should differ by roughly half the bits.
        val random = SeededRandom(7)
        var rejected = 0
        val trials = 40
        repeat(trials) {
            val a = PhotoHasher.hash(IntArray(width * height) { random.nextInt(256) })
            val b = PhotoHasher.hash(IntArray(width * height) { random.nextInt(256) })
            if (!PhotoHasher.matches(a, b)) rejected++
        }
        assertEquals(trials, rejected, "every unrelated pair should be rejected")
    }

    @Test
    @DisplayName("confidence runs from 0 to 1")
    fun confidenceRange() {
        val a = PhotoHasher.hash(gradient())
        assertEquals(1.0, PhotoHasher.confidence(a, a), 1e-9)

        val reversed = IntArray(width * height) { index ->
            (width - 1 - (index % width)) * 28
        }
        val b = PhotoHasher.hash(reversed)
        assertEquals(0.0, PhotoHasher.confidence(a, b), 1e-9)
    }

    @Test
    @DisplayName("mismatched grid sizes never match")
    fun mismatchedSizes() {
        val a = PhotoHasher.hash(gradient())
        val b = a.copy(width = 5, height = 5)
        assertEquals(Int.MAX_VALUE, PhotoHasher.distance(a, b))
        assertFalse(PhotoHasher.matches(a, b))
        assertEquals(0.0, PhotoHasher.confidence(a, b), 1e-9)
    }

    @Test
    @DisplayName("a wrong-sized sample array is rejected loudly")
    fun rejectsWrongSize() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            PhotoHasher.hash(IntArray(10))
        }
        assertTrue(error.message!!.contains("expected"))
    }
}

@DisplayName("Photo and code challenges")
class CameraChallengeTest {

    private fun fingerprintOf(seed: Long): PhotoFingerprint {
        val random = SeededRandom(seed)
        val width = PhotoFingerprint.DEFAULT_WIDTH
        val height = PhotoFingerprint.DEFAULT_HEIGHT
        return PhotoHasher.hash(IntArray(width * height) { random.nextInt(256) })
    }

    @Test
    @DisplayName("a photo of the registered place is accepted")
    fun photoAccepted() {
        val target = PhotoTarget("t1", "the kitchen kettle", fingerprintOf(1))
        val challenge = PhotoChallenge(target)
        assertEquals(MissionAttemptResult.Correct, challenge.check(target.fingerprint))
    }

    @Test
    @DisplayName("a photo of somewhere else is rejected with a useful reason")
    fun photoRejected() {
        val challenge = PhotoChallenge(PhotoTarget("t1", "the kitchen kettle", fingerprintOf(1)))
        val result = challenge.check(fingerprintOf(2))
        assertEquals(
            MissionAttemptResult.Incorrect(MissionFailureReason.WRONG_TARGET),
            result
        )
    }

    @Test
    @DisplayName("the photo prompt names the place, not just 'take a photo'")
    fun photoPrompt() {
        val challenge = PhotoChallenge(PhotoTarget("t1", "the bathroom sink", fingerprintOf(3)))
        assertTrue(challenge.prompt.contains("the bathroom sink"))
    }

    @Test
    @DisplayName("a code matches exactly, ignoring surrounding whitespace")
    fun codeMatching() {
        val target = CodeTarget("c1", "the cereal box", "8901234567890")
        assertEquals(MissionAttemptResult.Correct, target.check("8901234567890"))
        assertEquals(MissionAttemptResult.Correct, target.check("  8901234567890 "))
        assertEquals(
            MissionAttemptResult.Incorrect(MissionFailureReason.WRONG_TARGET),
            target.check("8901234567891")
        )
    }

    @Test
    @DisplayName("challenge fingerprints are distinct per target")
    fun fingerprints() {
        val a = PhotoChallenge(PhotoTarget("a", "sink", fingerprintOf(1)))
        val b = PhotoChallenge(PhotoTarget("b", "kettle", fingerprintOf(1)))
        assertTrue(a.fingerprint != b.fingerprint)
    }
}
