package com.alarmy.core.mission

import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.support.SeededRandom

/** A sequence of tile indices the user must reproduce in order. */
data class MemoryChallenge(
    val sequence: List<Int>,
    val gridSize: Int,
    /** How long each tile is lit, in milliseconds. */
    val displayMillis: Long
) : MissionChallenge {
    override val fingerprint: String get() = "memory:$gridSize:${sequence.joinToString("-")}"
    override val prompt: String get() = "Repeat the sequence"

    val tileCount: Int get() = gridSize * gridSize

    /** Validates a partial entry, so a wrong tap is caught immediately. */
    fun checkPrefix(entered: List<Int>): MissionAttemptResult {
        if (entered.size > sequence.size) {
            return MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
        }
        entered.forEachIndexed { index, tile ->
            if (sequence[index] != tile) {
                return MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
            }
        }
        return MissionAttemptResult.Correct
    }

    fun isComplete(entered: List<Int>): Boolean = entered == sequence
}

/**
 * Builds memory sequences.
 *
 * Immediate repeats are excluded: tapping the same tile twice in a row is
 * remembered as one event, so it makes the sequence shorter than it looks.
 */
class MemorySequenceGenerator {

    fun generate(random: SeededRandom, difficulty: MissionDifficulty): MemoryChallenge {
        val gridSize = when (difficulty) {
            MissionDifficulty.VERY_EASY, MissionDifficulty.EASY -> 2
            MissionDifficulty.MEDIUM, MissionDifficulty.HARD -> 3
            MissionDifficulty.VERY_HARD -> 4
        }
        val length = when (difficulty) {
            MissionDifficulty.VERY_EASY -> 3
            MissionDifficulty.EASY -> 4
            MissionDifficulty.MEDIUM -> 5
            MissionDifficulty.HARD -> 6
            MissionDifficulty.VERY_HARD -> 7
        }
        val displayMillis = when (difficulty) {
            MissionDifficulty.VERY_EASY -> 900L
            MissionDifficulty.EASY -> 800L
            MissionDifficulty.MEDIUM -> 650L
            MissionDifficulty.HARD -> 520L
            MissionDifficulty.VERY_HARD -> 420L
        }

        val tileCount = gridSize * gridSize
        val sequence = mutableListOf<Int>()
        repeat(length) {
            var tile = random.nextInt(tileCount)
            // Avoid an immediate repeat, which reads as a single tap.
            if (sequence.isNotEmpty() && tile == sequence.last()) {
                tile = (tile + 1 + random.nextInt(tileCount - 1)) % tileCount
            }
            sequence += tile
        }

        return MemoryChallenge(sequence, gridSize, displayMillis)
    }
}

/** A phrase the user must retype exactly. */
data class TypingChallenge(
    val phrase: String,
    val caseSensitive: Boolean
) : MissionChallenge {
    override val fingerprint: String get() = "typing:${phrase.hashCode()}"
    override val prompt: String get() = "Type this exactly"

    fun check(input: String): MissionAttemptResult {
        val expected = if (caseSensitive) phrase else phrase.lowercase()
        val actual = if (caseSensitive) input else input.lowercase()

        // Only leading/trailing whitespace is forgiven; internal spacing and
        // punctuation must match, because that is the part requiring attention.
        return if (expected.trim() == actual.trim()) MissionAttemptResult.Correct
        else MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
    }

    fun progress(input: String): Double {
        if (phrase.isEmpty()) return 1.0
        val expected = if (caseSensitive) phrase else phrase.lowercase()
        val actual = if (caseSensitive) input else input.lowercase()

        var matched = 0
        while (matched < minOf(expected.length, actual.length) &&
            expected[matched] == actual[matched]
        ) {
            matched += 1
        }
        return matched.toDouble() / expected.length
    }
}

/**
 * Supplies typing phrases.
 *
 * The phrases are intentionally mundane and slightly odd. Familiar quotations
 * get typed from muscle memory, which defeats the point; an unfamiliar sentence
 * has to actually be read.
 */
class TypingPhraseProvider {

    private val short = listOf(
        "The kettle is already boiling",
        "Cold water wakes the face",
        "Sixteen steps to the window",
        "The curtains open eastward",
        "Coffee grounds and lemon peel",
        "Nine minutes is not enough",
        "The floor is colder than expected",
        "Yesterday's plan starts today"
    )

    private val medium = listOf(
        "A folded map of a city I have never visited",
        "The third stair from the top always creaks loudly",
        "Rain on the window sounds nothing like applause",
        "Two spoons of sugar and a very long silence",
        "The bus leaves whether or not I am on it",
        "Every excuse sounds reasonable at this hour",
        "Half a plan executed beats a whole plan postponed",
        "The alarm has already done its part of the work"
    )

    private val long = listOf(
        "Somewhere between the fourth snooze and the first coffee, the day quietly decided itself",
        "The difference between waking up and getting up is roughly eleven feet of cold floor",
        "I have negotiated with this alarm before, and I have never once won the argument",
        "There is a version of this morning where I am already outside, and it is not this one",
        "Twenty-three minutes of extra sleep costs approximately one entire useful hour",
        "The list I wrote last night was written by an optimist who is no longer available",
        "Nothing on the schedule cares how convincing the pillow's argument currently sounds",
        "By the time this sentence is finished, standing up will have become the easier option"
    )

    fun generate(random: SeededRandom, difficulty: MissionDifficulty): TypingChallenge {
        val pool = when (difficulty) {
            MissionDifficulty.VERY_EASY, MissionDifficulty.EASY -> short
            MissionDifficulty.MEDIUM, MissionDifficulty.HARD -> medium
            MissionDifficulty.VERY_HARD -> long
        }
        return TypingChallenge(
            phrase = random.pick(pool),
            caseSensitive = difficulty.level >= MissionDifficulty.HARD.level
        )
    }

    /** All phrases, used by tests to assert none are duplicated. */
    fun allPhrases(): List<String> = short + medium + long
}
