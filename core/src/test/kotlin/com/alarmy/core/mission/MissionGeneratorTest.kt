package com.alarmy.core.mission

import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType
import com.alarmy.core.support.SeededRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MathMissionGeneratorTest {

    private val generator = MathMissionGenerator()

    @Test
    fun `answers are never negative at any difficulty`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(300) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                assertTrue(
                    challenge.answer >= 0,
                    "${difficulty.displayName}: ${challenge.expression} = ${challenge.answer}"
                )
            }
        }
    }

    @Test
    fun `the stated answer is the arithmetically correct one`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                assertEquals(
                    challenge.answer,
                    evaluate(challenge.expression),
                    "mismatch for ${challenge.expression}"
                )
            }
        }
    }

    @Test
    fun `multiplication never uses a trivial operand`() {
        repeat(500) { seed ->
            MissionDifficulty.entries.forEach { difficulty ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                Regex("""(\d+) × (\d+)""").findAll(challenge.expression).forEach { match ->
                    val a = match.groupValues[1].toInt()
                    val b = match.groupValues[2].toInt()
                    assertTrue(a >= 2 && b >= 2, "trivial multiplication in ${challenge.expression}")
                }
            }
        }
    }

    @Test
    fun `the same seed always produces the same problem`() {
        val a = generator.generate(SeededRandom(42), MissionDifficulty.HARD)
        val b = generator.generate(SeededRandom(42), MissionDifficulty.HARD)

        assertEquals(a.expression, b.expression)
        assertEquals(a.answer, b.answer)
    }

    @Test
    fun `different seeds mostly produce different problems`() {
        val expressions = (0 until 200)
            .map { generator.generate(SeededRandom(it.toLong()), MissionDifficulty.MEDIUM).expression }
            .toSet()

        assertTrue(expressions.size > 100, "only ${expressions.size} distinct problems in 200")
    }

    @Test
    fun `harder difficulties use more operations`() {
        val easy = generator.generate(SeededRandom(7), MissionDifficulty.VERY_EASY)
        val hard = generator.generate(SeededRandom(7), MissionDifficulty.VERY_HARD)

        assertTrue(operatorCount(easy.expression) < operatorCount(hard.expression))
    }

    @Test
    fun `a generated set has no duplicates`() {
        val set = generator.generateSet(SeededRandom(1), MissionDifficulty.MEDIUM, 5)

        assertEquals(5, set.size)
        assertEquals(5, set.map { it.fingerprint }.toSet().size)
    }

    @Test
    fun `checking accepts the answer and rejects everything else`() {
        val challenge = generator.generate(SeededRandom(3), MissionDifficulty.EASY)

        assertEquals(MissionAttemptResult.Correct, challenge.check(challenge.answer.toString()))
        assertEquals(MissionAttemptResult.Correct, challenge.check(" ${challenge.answer} "))
        assertTrue(challenge.check("${challenge.answer + 1}") is MissionAttemptResult.Incorrect)
        assertTrue(challenge.check("") is MissionAttemptResult.Incorrect)
        assertTrue(challenge.check("abc") is MissionAttemptResult.Incorrect)
    }

    private fun operatorCount(expression: String): Int =
        expression.count { it == '+' || it == '-' || it == '×' }

    /** Minimal evaluator honouring × before + and −, used to verify the generator. */
    private fun evaluate(expression: String): Int {
        val tokens = expression.split(" ")
        val values = mutableListOf<Int>()
        val operators = mutableListOf<String>()

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (index % 2 == 0) {
                var value = token.toInt()
                // Fold any multiplication immediately.
                while (index + 1 < tokens.size && tokens[index + 1] == "×") {
                    value *= tokens[index + 2].toInt()
                    index += 2
                }
                values += value
            } else {
                operators += token
            }
            index += 1
        }

        var result = values.first()
        operators.forEachIndexed { i, operator ->
            result = if (operator == "+") result + values[i + 1] else result - values[i + 1]
        }
        return result
    }
}

class MissingSymbolGeneratorTest {

    private val generator = MissingSymbolGenerator()

    @Test
    fun `the answer is absent from the visible row`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                assertFalse(
                    challenge.row.filterNotNull().contains(challenge.answer),
                    "answer ${challenge.answer} visible in ${challenge.row}"
                )
            }
        }
    }

    @Test
    fun `there is exactly one gap`() {
        repeat(200) { seed ->
            val challenge = generator.generate(SeededRandom(seed.toLong()), MissionDifficulty.MEDIUM)
            assertEquals(1, challenge.row.count { it == null })
        }
    }

    @Test
    fun `the options always contain the answer`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                assertTrue(challenge.options.contains(challenge.answer))
            }
        }
    }

    @Test
    fun `there are always several plausible options`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                assertTrue(
                    challenge.options.size >= 4,
                    "${difficulty.displayName} offered only ${challenge.options.size} options"
                )
                assertEquals(
                    challenge.options.size,
                    challenge.options.toSet().size,
                    "duplicate options"
                )
            }
        }
    }

    @Test
    fun `no distractor is already visible in the row`() {
        // A visible symbol could be eliminated without solving anything.
        repeat(300) { seed ->
            val challenge = generator.generate(SeededRandom(seed.toLong()), MissionDifficulty.HARD)
            val visible = challenge.row.filterNotNull().toSet()
            challenge.options.filter { it != challenge.answer }.forEach { distractor ->
                assertFalse(visible.contains(distractor), "distractor $distractor was on screen")
            }
        }
    }

    @Test
    fun `the row never repeats a symbol`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                val symbols = challenge.row.filterNotNull()
                assertEquals(symbols.size, symbols.toSet().size, "repeated symbol in $symbols")
            }
        }
    }

    @Test
    fun `checking accepts only the answer`() {
        val challenge = generator.generate(SeededRandom(11), MissionDifficulty.MEDIUM)
        val wrong = challenge.options.first { it != challenge.answer }

        assertEquals(MissionAttemptResult.Correct, challenge.check(challenge.answer))
        assertTrue(challenge.check(wrong) is MissionAttemptResult.Incorrect)
    }
}

class MemorySequenceGeneratorTest {

    private val generator = MemorySequenceGenerator()

    @Test
    fun `sequences never repeat a tile back to back`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(300) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                challenge.sequence.zipWithNext().forEach { (a, b) ->
                    assertFalse(a == b, "immediate repeat in ${challenge.sequence}")
                }
            }
        }
    }

    @Test
    fun `every tile index is inside the grid`() {
        MissionDifficulty.entries.forEach { difficulty ->
            repeat(200) { seed ->
                val challenge = generator.generate(SeededRandom(seed.toLong()), difficulty)
                challenge.sequence.forEach { tile ->
                    assertTrue(tile in 0 until challenge.tileCount)
                }
            }
        }
    }

    @Test
    fun `sequences get longer and faster as difficulty rises`() {
        val easy = generator.generate(SeededRandom(5), MissionDifficulty.VERY_EASY)
        val hard = generator.generate(SeededRandom(5), MissionDifficulty.VERY_HARD)

        assertTrue(hard.sequence.size > easy.sequence.size)
        assertTrue(hard.displayMillis < easy.displayMillis)
        assertTrue(hard.gridSize > easy.gridSize)
    }

    @Test
    fun `a wrong tap is caught on the tap that made it wrong`() {
        val challenge = generator.generate(SeededRandom(9), MissionDifficulty.MEDIUM)
        val correctPrefix = challenge.sequence.take(2)
        val wrongTile = (challenge.sequence[2] + 1) % challenge.tileCount

        assertEquals(MissionAttemptResult.Correct, challenge.checkPrefix(correctPrefix))
        assertTrue(
            challenge.checkPrefix(correctPrefix + wrongTile) is MissionAttemptResult.Incorrect
        )
    }

    @Test
    fun `completion requires the whole sequence`() {
        val challenge = generator.generate(SeededRandom(9), MissionDifficulty.MEDIUM)

        assertFalse(challenge.isComplete(challenge.sequence.dropLast(1)))
        assertTrue(challenge.isComplete(challenge.sequence))
    }

    @Test
    fun `overrunning the sequence is rejected`() {
        val challenge = generator.generate(SeededRandom(9), MissionDifficulty.MEDIUM)
        val tooLong = challenge.sequence + 0

        assertTrue(challenge.checkPrefix(tooLong) is MissionAttemptResult.Incorrect)
    }
}

class TypingPhraseProviderTest {

    private val provider = TypingPhraseProvider()

    @Test
    fun `no phrase is duplicated`() {
        val phrases = provider.allPhrases()
        assertEquals(phrases.size, phrases.toSet().size)
        assertEquals(24, phrases.size)
    }

    @Test
    fun `harder difficulties use longer phrases and demand exact case`() {
        val easy = provider.generate(SeededRandom(2), MissionDifficulty.VERY_EASY)
        val hard = provider.generate(SeededRandom(2), MissionDifficulty.VERY_HARD)

        assertTrue(hard.phrase.length > easy.phrase.length)
        assertFalse(easy.caseSensitive)
        assertTrue(hard.caseSensitive)
    }

    @Test
    fun `an exact match is accepted and surrounding whitespace forgiven`() {
        val challenge = provider.generate(SeededRandom(4), MissionDifficulty.EASY)

        assertEquals(MissionAttemptResult.Correct, challenge.check(challenge.phrase))
        assertEquals(MissionAttemptResult.Correct, challenge.check("  ${challenge.phrase}  "))
    }

    @Test
    fun `case is forgiven only below hard`() {
        val easy = provider.generate(SeededRandom(4), MissionDifficulty.EASY)
        val hard = provider.generate(SeededRandom(4), MissionDifficulty.VERY_HARD)

        assertEquals(MissionAttemptResult.Correct, easy.check(easy.phrase.uppercase()))
        assertTrue(hard.check(hard.phrase.uppercase()) is MissionAttemptResult.Incorrect)
    }

    @Test
    fun `a truncated or altered phrase is rejected`() {
        val challenge = provider.generate(SeededRandom(4), MissionDifficulty.MEDIUM)

        assertTrue(challenge.check(challenge.phrase.dropLast(1)) is MissionAttemptResult.Incorrect)
        assertTrue(challenge.check("${challenge.phrase}!") is MissionAttemptResult.Incorrect)
    }

    @Test
    fun `progress reflects how much has been typed correctly`() {
        val challenge = provider.generate(SeededRandom(4), MissionDifficulty.EASY)

        assertEquals(0.0, challenge.progress(""))
        assertEquals(1.0, challenge.progress(challenge.phrase))
        assertTrue(challenge.progress(challenge.phrase.take(challenge.phrase.length / 2)) > 0.4)
        assertEquals(0.0, challenge.progress("zzz"))
    }
}

class MissionLibraryTest {

    @Test
    fun `every mission type has a descriptor`() {
        MissionType.entries.forEach { type ->
            val descriptor = MissionLibrary.descriptor(type)
            assertNotNull(descriptor)
            assertTrue(descriptor.summary.isNotBlank())
            assertTrue(descriptor.howItWorks.isNotBlank())
        }
        assertEquals(MissionType.entries.size, MissionLibrary.descriptors.size)
    }

    @Test
    fun `every fallback chain terminates in a mission needing no permissions`() {
        MissionType.entries.forEach { type ->
            val chain = type.fallbackChain
            assertTrue(chain.isNotEmpty(), "${type.name} has no fallback")
            assertTrue(
                chain.last().requiredPermissions.isEmpty(),
                "${type.name} falls back to a mission that can itself be unavailable"
            )
        }
    }

    @Test
    fun `a camera mission falls back when the camera is unavailable`() {
        val available = MissionLibrary.available(hasCamera = false, cameraGranted = false)
        val resolved = MissionLibrary.resolve(
            MissionConfig(MissionType.QR, MissionDifficulty.MEDIUM, 1),
            available
        )

        assertFalse(resolved.type == MissionType.QR)
        assertTrue(resolved.type in available)
        assertTrue(
            resolved.targetCount in MissionConfig.targetRange(resolved.type),
            "the substitute must arrive with a legal target count"
        )
    }

    @Test
    fun `a step mission falls back without activity recognition`() {
        val available = MissionLibrary.available(activityRecognitionGranted = false)
        val resolved = MissionLibrary.resolve(
            MissionConfig(MissionType.STEPS, MissionDifficulty.MEDIUM, 30),
            available
        )

        assertEquals(MissionType.SHAKE, resolved.type)
    }

    @Test
    fun `an available mission is returned untouched`() {
        val config = MissionConfig(MissionType.MATH, MissionDifficulty.HARD, 4)
        assertEquals(config, MissionLibrary.resolve(config, MissionLibrary.available()))
    }

    @Test
    fun `a combination resolves each child independently`() {
        val available = MissionLibrary.available(hasCamera = false, cameraGranted = false)
        val combination = MissionConfig(
            type = MissionType.COMBINATION,
            difficulty = MissionDifficulty.MEDIUM,
            targetCount = 2,
            children = listOf(
                MissionConfig(MissionType.MATH, MissionDifficulty.EASY, 3),
                MissionConfig(MissionType.QR, MissionDifficulty.EASY, 1)
            )
        )

        val resolved = MissionLibrary.resolve(combination, available)

        assertEquals(MissionType.COMBINATION, resolved.type)
        assertEquals(MissionType.MATH, resolved.children[0].type)
        assertFalse(resolved.children[1].type == MissionType.QR)
    }

    @Test
    fun `with everything available nothing is substituted`() {
        val available = MissionLibrary.available()
        MissionType.entries.forEach { type ->
            val config = MissionConfig(type, MissionDifficulty.MEDIUM, MissionConfig.defaultTargetCount(type))
            assertEquals(type, MissionLibrary.resolve(config, available).type)
        }
    }
}
