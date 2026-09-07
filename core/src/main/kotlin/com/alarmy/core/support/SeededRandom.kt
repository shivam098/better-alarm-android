package com.alarmy.core.support

/**
 * Deterministic, seedable RNG (SplitMix64).
 *
 * Challenges are generated through this so a given morning is reproducible in
 * tests and in bug reports, while remaining unpredictable in normal use.
 *
 * Kotlin's own `Random(seed)` would do the job, but pinning the exact algorithm
 * keeps generated challenges identical to the iOS build — useful when the same
 * bug report has to be reproduced on both platforms.
 */
class SeededRandom(seed: Long) {

    // Avoid the degenerate all-zero state.
    private var state: Long = if (seed == 0L) GOLDEN_GAMMA else seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in `0 until bound`. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        // ushr 1 keeps it non-negative before the modulo.
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Uniform in `range`, inclusive of both ends. */
    fun nextInt(range: IntRange): Int =
        range.first + nextInt(range.last - range.first + 1)

    fun nextBoolean(): Boolean = (nextLong() ushr 1) % 2L == 0L

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Fisher–Yates, so shuffles are reproducible from the seed. */
    fun <T> shuffled(items: List<T>): List<T> {
        val copy = items.toMutableList()
        for (i in copy.indices.reversed()) {
            val j = nextInt(i + 1)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

        /**
         * Seed derived from an occurrence key, so one alarm firing is
         * reproducible for diagnostics but differs between mornings.
         */
        fun forOccurrence(key: String, salt: Long = 0L): SeededRandom {
            var hash = -0x340d631b7bdddcdbL // 0xCBF29CE484222325 (FNV-1a offset basis)
            for (byte in key.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 0x100000001B3L
            }
            return SeededRandom(hash xor salt)
        }
    }
}
