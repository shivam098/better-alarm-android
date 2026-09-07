package com.alarmy.core.mission

import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.support.SeededRandom
import kotlin.math.abs

/** A generated arithmetic problem. */
data class MathChallenge(
    val expression: String,
    val answer: Int,
    val difficulty: MissionDifficulty
) : MissionChallenge {
    override val fingerprint: String get() = "math:$expression"
    override val prompt: String get() = "$expression = ?"

    fun check(input: String): MissionAttemptResult {
        val parsed = input.trim().toIntOrNull()
            ?: return MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
        return if (parsed == answer) MissionAttemptResult.Correct
        else MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
    }
}

/**
 * Generates arithmetic that is hard enough to require attention but never
 * requires pen and paper.
 *
 * Two rules shape every problem, and both come from the same principle — the
 * mission must be *annoying*, not *impossible*:
 *
 *  * Intermediate and final values stay whole and non-negative. Nobody should
 *    meet a negative number at 6am.
 *  * Trivial operands (0 and 1) are excluded from multiplication, because
 *    "6 x 1" is not a problem, it is a formality.
 */
class MathMissionGenerator {

    fun generate(random: SeededRandom, difficulty: MissionDifficulty): MathChallenge {
        val expression = when (difficulty) {
            MissionDifficulty.VERY_EASY -> singleOperation(random, 1..9, listOf('+'))
            MissionDifficulty.EASY -> singleOperation(random, 2..12, listOf('+', '-'))
            MissionDifficulty.MEDIUM -> singleOperation(random, 3..19, listOf('+', '-', '*'))
            MissionDifficulty.HARD -> twoOperations(random, 2..15)
            MissionDifficulty.VERY_HARD -> threeOperations(random, 3..19)
        }
        return MathChallenge(expression.first, expression.second, difficulty)
    }

    /** [count] distinct problems for a multi-step mission. */
    fun generateSet(
        random: SeededRandom,
        difficulty: MissionDifficulty,
        count: Int
    ): List<MathChallenge> {
        val results = mutableListOf<MathChallenge>()
        val seen = mutableSetOf<String>()
        var guard = 0

        while (results.size < count && guard < count * 20) {
            val candidate = generate(random, difficulty)
            if (seen.add(candidate.fingerprint)) results += candidate
            guard += 1
        }
        // If the space is too small to fill, ship what we have rather than
        // hanging: a short mission beats an alarm that cannot be stopped.
        while (results.size < count) results += generate(random, difficulty)
        return results
    }

    private fun singleOperation(
        random: SeededRandom,
        range: IntRange,
        operators: List<Char>
    ): Pair<String, Int> {
        val operator = random.pick(operators)
        var a = random.nextInt(range)
        var b = random.nextInt(range)

        return when (operator) {
            '+' -> "$a + $b" to (a + b)
            '-' -> {
                // Keep the result non-negative.
                if (b > a) { val t = a; a = b; b = t }
                "$a - $b" to (a - b)
            }
            else -> {
                // Exclude 0 and 1, which make multiplication trivial.
                val x = multiplicand(random, range)
                val y = multiplicand(random, range)
                "$x × $y" to (x * y)
            }
        }
    }

    private fun twoOperations(random: SeededRandom, range: IntRange): Pair<String, Int> {
        val a = random.nextInt(range)
        val b = multiplicand(random, 2..9)
        val c = random.nextInt(range)

        return if (random.nextBoolean()) {
            // a + b × c — deliberately exercises operator precedence.
            "$a + $b × $c" to (a + b * c)
        } else {
            val product = b * c
            val total = product + a
            "$total - $b × $c" to (total - product)
        }
    }

    private fun threeOperations(random: SeededRandom, range: IntRange): Pair<String, Int> {
        val a = multiplicand(random, 2..9)
        val b = multiplicand(random, 2..9)
        val c = random.nextInt(range)
        val d = random.nextInt(range)

        val value = a * b + c - d
        return if (value < 0) {
            "$a × $b + $d - $c" to (a * b + d - c)
        } else {
            "$a × $b + $c - $d" to value
        }
    }

    private fun multiplicand(random: SeededRandom, range: IntRange): Int {
        val lower = maxOf(range.first, 2)
        val upper = maxOf(lower, range.last)
        return random.nextInt(lower..upper)
    }
}

/** A row of ordered symbols with exactly one removed. */
data class MissingSymbolChallenge(
    /** The displayed row, with `null` marking the gap. */
    val row: List<String?>,
    val answer: String,
    val options: List<String>,
    val setName: String
) : MissionChallenge {
    override val fingerprint: String get() = "symbol:$setName:${row.joinToString(",") { it ?: "_" }}"
    override val prompt: String get() = "Which symbol is missing?"

    fun check(choice: String): MissionAttemptResult =
        if (choice == answer) MissionAttemptResult.Correct
        else MissionAttemptResult.Incorrect(MissionFailureReason.INCORRECT_ANSWER)
}

/**
 * Builds "find the missing symbol" challenges from ordered sets.
 *
 * The distractors are the whole design. Drawing them at random from the
 * alphabet makes the answer obvious by elimination; drawing them from
 * *neighbouring positions in the same ordered set* forces the user to actually
 * scan the row and work out which position is empty.
 */
class MissingSymbolGenerator {

    data class SymbolSet(val name: String, val symbols: List<String>)

    private val sets = listOf(
        SymbolSet("letters", ('A'..'Z').map(Char::toString)),
        SymbolSet("digits", (0..9).map(Int::toString)),
        SymbolSet(
            "greek",
            listOf(
                "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ",
                "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ", "φ", "χ", "ψ", "ω"
            )
        ),
        SymbolSet(
            "shapes",
            listOf(
                "●", "■", "▲", "◆", "★", "✚", "✦", "❖",
                "◐", "◑", "◒", "◓", "◇", "△", "▽", "☗"
            )
        ),
        SymbolSet("arrows", listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖"))
    )

    fun generate(random: SeededRandom, difficulty: MissionDifficulty): MissingSymbolChallenge {
        val desiredLength = when (difficulty) {
            MissionDifficulty.VERY_EASY -> 4
            MissionDifficulty.EASY -> 5
            MissionDifficulty.MEDIUM -> 6
            MissionDifficulty.HARD -> 8
            MissionDifficulty.VERY_HARD -> 10
        }
        val optionCount = if (difficulty.level >= MissionDifficulty.HARD.level) 6 else 4

        // A set is only usable if it has enough symbols left over, after the row
        // is drawn, to supply distractors. Without this a 10-symbol set and a
        // 10-long row would consume every symbol and leave the correct answer as
        // the only option on screen — a puzzle that solves itself.
        val required = desiredLength + optionCount - 1
        val usable = sets.filter { it.symbols.size >= required }
        val set = if (usable.isEmpty()) sets.first { it.name == "letters" } else random.pick(usable)

        val rowLength = minOf(desiredLength, set.symbols.size - (optionCount - 1))

        // A step coprime with the set size guarantees the run never repeats a
        // symbol, whatever the starting point — so the answer appears exactly
        // once, in the gap.
        val step = coprimeStep(random, set.symbols.size, difficulty)
        val start = random.nextInt(set.symbols.size)

        val sequence = (0 until rowLength).map { index ->
            set.symbols[(start + index * step) % set.symbols.size]
        }

        val gapIndex = random.nextInt(rowLength)
        val answer = sequence[gapIndex]
        val row = sequence.mapIndexed { index, symbol -> if (index == gapIndex) null else symbol }

        return MissingSymbolChallenge(
            row = row,
            answer = answer,
            options = buildOptions(random, set, answer, sequence, optionCount),
            setName = set.name
        )
    }

    /**
     * Distractors are drawn from the same ordered set and, where possible, from
     * positions adjacent to the answer — near misses rather than obvious
     * outsiders.
     */
    private fun buildOptions(
        random: SeededRandom,
        set: SymbolSet,
        answer: String,
        shown: List<String>,
        count: Int
    ): List<String> {
        val answerIndex = set.symbols.indexOf(answer)
        val options = linkedSetOf(answer)

        val neighbours = listOf(1, -1, 2, -2, 3, -3, 4, -4)
            .map { offset ->
                val index = ((answerIndex + offset) % set.symbols.size + set.symbols.size) %
                    set.symbols.size
                set.symbols[index]
            }

        for (candidate in neighbours) {
            if (options.size >= count) break
            // Never offer a symbol already visible in the row — it would be
            // eliminable without solving anything.
            if (candidate != answer && candidate !in shown) options += candidate
        }

        // Top up from the rest of the set if neighbours were exhausted.
        for (candidate in set.symbols) {
            if (options.size >= count) break
            if (candidate != answer && candidate !in shown) options += candidate
        }

        return random.shuffled(options.toList())
    }

    private fun coprimeStep(random: SeededRandom, size: Int, difficulty: MissionDifficulty): Int {
        if (difficulty == MissionDifficulty.VERY_EASY) return 1

        val maxStep = when (difficulty) {
            MissionDifficulty.EASY -> 2
            MissionDifficulty.MEDIUM -> 3
            MissionDifficulty.HARD -> 4
            else -> 5
        }
        val candidates = (1..maxStep).filter { gcd(it, size) == 1 }
        return if (candidates.isEmpty()) 1 else random.pick(candidates)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }
}
