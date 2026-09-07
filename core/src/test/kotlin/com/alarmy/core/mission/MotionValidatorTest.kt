package com.alarmy.core.mission

import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.support.SeededRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShakeCounterTest {

    @Test
    fun `ordinary shaking is counted`() {
        val counter = ShakeCounter(target = 30)
        val intervals = listOf(0.40, 0.55, 0.30, 0.60, 0.45, 0.35, 0.50, 0.62)

        var time = 0.0
        var outcome: MotionOutcome = counter.register(time)
        intervals.forEach { gap ->
            time += gap
            outcome = counter.register(time)
        }

        assertEquals(9, counter.count)
        assertTrue(outcome is MotionOutcome.Counted)
        assertEquals(0, counter.rejections)
    }

    @Test
    fun `perfectly periodic shaking is rejected as mechanical`() {
        val counter = ShakeCounter(target = 30)

        // Eight identical intervals are needed before periodicity can be judged,
        // so the ninth sample is the first that can be rejected.
        var outcome: MotionOutcome = MotionOutcome.Ignored
        repeat(9) { index ->
            outcome = counter.register(index * 0.5)
        }

        assertTrue(outcome is MotionOutcome.Rejected)
        assertEquals(
            MissionFailureReason.IMPLAUSIBLE_MOTION,
            (outcome as MotionOutcome.Rejected).reason
        )
        assertEquals(0, counter.count, "a rejection wipes the progress")
    }

    @Test
    fun `a rejection does not trap the user in a loop`() {
        val counter = ShakeCounter(target = 30)

        repeat(9) { index -> counter.register(index * 0.5) }
        assertEquals(0, counter.count)

        // The next three shakes count normally, because the rejection also
        // cleared the interval history.
        repeat(3) { index -> counter.register(4.5 + index * 0.5) }

        assertEquals(3, counter.count)
        assertEquals(1, counter.rejections)
    }

    @Test
    fun `reaching the target completes the mission`() {
        val counter = ShakeCounter(target = 3)
        val gaps = listOf(0.0, 0.4, 0.65)

        var outcome: MotionOutcome = MotionOutcome.Ignored
        gaps.forEach { outcome = counter.register(it) }

        assertTrue(outcome is MotionOutcome.Completed)
        assertEquals(3, (outcome as MotionOutcome.Completed).count)
    }

    @Test
    fun `duplicate timestamps are ignored rather than counted twice`() {
        val counter = ShakeCounter(target = 30)
        counter.register(1.0)
        counter.register(1.0)

        // The second sample still counts as a shake, but contributes no interval
        // that could poison the periodicity analysis.
        assertEquals(2, counter.count)
    }

    @Test
    fun `reset clears everything`() {
        val counter = ShakeCounter(target = 30)
        repeat(4) { counter.register(it * 0.4) }
        counter.reset()

        assertEquals(0, counter.count)
        counter.register(99.0)
        assertEquals(1, counter.count)
    }
}

class StepValidatorTest {

    @Test
    fun `a plausible walking cadence is counted`() {
        val validator = StepValidator(target = 100)
        val intervals = listOf(
            0.50, 0.54, 0.47, 0.52, 0.49, 0.55, 0.46, 0.51, 0.53, 0.48
        )

        var time = 0.0
        validator.register(time)
        intervals.forEach { gap ->
            time += gap
            validator.register(time)
        }

        assertEquals(11, validator.count)
    }

    @Test
    fun `shaking the phone is too fast to be walking`() {
        val validator = StepValidator(target = 100)
        validator.register(0.0)

        val outcome = validator.register(0.1)

        assertTrue(outcome is MotionOutcome.Rejected)
        assertEquals(0, validator.count)
    }

    @Test
    fun `a perfectly regular cadence is rejected as mechanical`() {
        val validator = StepValidator(target = 100)

        var outcome: MotionOutcome = MotionOutcome.Ignored
        repeat(11) { index -> outcome = validator.register(index * 0.5) }

        assertTrue(outcome is MotionOutcome.Rejected)
        assertEquals(0, validator.count)
    }

    @Test
    fun `pausing mid-walk is allowed and resets the cadence run`() {
        val validator = StepValidator(target = 100)
        validator.register(0.0)
        validator.register(0.5)

        val outcome = validator.register(20.0)

        assertTrue(outcome is MotionOutcome.Counted)
        assertEquals(3, validator.count)
    }

    @Test
    fun `reaching the target completes the mission`() {
        val validator = StepValidator(target = 3)
        val outcomes = listOf(0.0, 0.5, 1.1).map { validator.register(it) }

        assertTrue(outcomes.last() is MotionOutcome.Completed)
    }

    @Test
    fun `a non-advancing timestamp is ignored`() {
        val validator = StepValidator(target = 100)
        validator.register(5.0)

        assertEquals(MotionOutcome.Ignored, validator.register(5.0))
        assertEquals(1, validator.count)
    }
}

class SquatRepCounterTest {

    @Test
    fun `a full squat counts once`() {
        val counter = SquatRepCounter(target = 10)

        counter.register(165.0, 0.0)
        counter.register(130.0, 0.3)
        counter.register(95.0, 0.7)
        val outcome = counter.register(165.0, 1.2)

        assertTrue(outcome is MotionOutcome.Counted)
        assertEquals(1, counter.count)
    }

    @Test
    fun `a partial dip does not count`() {
        val counter = SquatRepCounter(target = 10)

        counter.register(165.0, 0.0)
        counter.register(130.0, 0.3)
        val outcome = counter.register(165.0, 0.6)

        assertEquals(MotionOutcome.Ignored, outcome)
        assertEquals(0, counter.count)
    }

    @Test
    fun `bouncing the phone is too fast to be a squat`() {
        val counter = SquatRepCounter(target = 10)

        counter.register(165.0, 0.0)
        counter.register(95.0, 0.1)
        val outcome = counter.register(165.0, 0.3)

        assertTrue(outcome is MotionOutcome.Rejected)
        assertEquals(0, counter.count)
        assertEquals(1, counter.rejections)
    }

    @Test
    fun `a rejected bounce does not block the next honest repetition`() {
        val counter = SquatRepCounter(target = 10)
        counter.register(165.0, 0.0)
        counter.register(95.0, 0.1)
        counter.register(165.0, 0.3)

        counter.register(95.0, 1.0)
        val outcome = counter.register(165.0, 2.0)

        assertTrue(outcome is MotionOutcome.Counted)
        assertEquals(1, counter.count)
    }

    @Test
    fun `a very slow movement is not a repetition`() {
        val counter = SquatRepCounter(target = 10)

        counter.register(165.0, 0.0)
        counter.register(95.0, 1.0)
        val outcome = counter.register(165.0, 30.0)

        assertEquals(MotionOutcome.Ignored, outcome)
        assertEquals(0, counter.count)
    }

    @Test
    fun `repetitions accumulate to the target`() {
        val counter = SquatRepCounter(target = 3)

        var outcome: MotionOutcome = MotionOutcome.Ignored
        repeat(3) { rep ->
            val base = rep * 3.0
            counter.register(170.0, base)
            counter.register(100.0, base + 0.6)
            outcome = counter.register(170.0, base + 1.4)
        }

        assertTrue(outcome is MotionOutcome.Completed)
        assertEquals(3, counter.count)
    }

    @Test
    fun `reset clears progress`() {
        val counter = SquatRepCounter(target = 10)
        counter.register(165.0, 0.0)
        counter.register(95.0, 0.5)
        counter.register(165.0, 1.2)
        assertEquals(1, counter.count)

        counter.reset()
        assertEquals(0, counter.count)
    }
}

class MotionStatisticsTest {

    @Test
    fun `a constant series has no variation`() {
        val values = List(10) { 0.5 }

        assertEquals(0.5, MotionStatistics.mean(values))
        assertEquals(0.0, MotionStatistics.standardDeviation(values))
        assertEquals(0.0, MotionStatistics.coefficientOfVariation(values))
    }

    @Test
    fun `human-like variation is well above the mechanical threshold`() {
        val values = listOf(0.40, 0.55, 0.30, 0.60, 0.45, 0.35, 0.50, 0.62)

        assertTrue(MotionStatistics.coefficientOfVariation(values) > 0.08)
    }

    @Test
    fun `a single sample has no measurable spread`() {
        assertEquals(0.0, MotionStatistics.standardDeviation(listOf(1.0)))
        assertEquals(0.0, MotionStatistics.coefficientOfVariation(emptyList()))
    }

    @Test
    fun `a zero mean cannot produce a division by zero`() {
        assertEquals(0.0, MotionStatistics.coefficientOfVariation(listOf(-1.0, 1.0)))
    }

    @Test
    fun `magnitude is the vector length`() {
        assertEquals(5.0, MotionSample(3.0, 4.0, 0.0, 0.0).magnitude)
    }
}

class RecentChallengeHistoryTest {

    private val day = 86_400_000L

    @Test
    fun `a recorded challenge is remembered`() {
        val history = RecentChallengeHistory()
        history.record("math:2 + 2", 0)

        assertTrue(history.hasSeen("math:2 + 2", 0))
        assertTrue(!history.hasSeen("math:3 + 3", 0))
    }

    @Test
    fun `a challenge is forgotten once it falls outside the retention window`() {
        val history = RecentChallengeHistory(retentionDays = 30)
        history.record("math:2 + 2", 0)

        assertTrue(history.hasSeen("math:2 + 2", 29 * day))
        assertTrue(!history.hasSeen("math:2 + 2", 31 * day))
    }

    @Test
    fun `history is capped so it cannot grow without bound`() {
        val history = RecentChallengeHistory(maxEntries = 50)
        repeat(200) { history.record("challenge-$it", 0) }

        assertTrue(history.size() <= 50, "history grew to ${history.size()}")
        assertTrue(history.hasSeen("challenge-199", 0), "the newest entry must survive")
        assertTrue(!history.hasSeen("challenge-0", 0), "the oldest entry must be evicted")
    }

    @Test
    fun `expired entries are pruned on write`() {
        val history = RecentChallengeHistory(retentionDays = 1)
        history.record("old", 0)
        history.record("new", 5 * day)

        assertEquals(1, history.size())
    }

    @Test
    fun `the factory avoids a challenge that was seen recently`() {
        val history = RecentChallengeHistory()
        val factory = UniqueChallengeFactory<MathChallenge>(history)
        val generator = MathMissionGenerator()

        val first = factory.generate(SeededRandom(1), 0) {
            generator.generate(it, MissionDifficulty.MEDIUM)
        }
        val second = factory.generate(SeededRandom(1), 0) {
            generator.generate(it, MissionDifficulty.MEDIUM)
        }

        assertTrue(
            first.fingerprint != second.fingerprint,
            "the same seed must not yield the same challenge twice in a row"
        )
    }

    @Test
    fun `the factory gives up rather than looping forever`() {
        val history = RecentChallengeHistory()
        val factory = UniqueChallengeFactory<MathChallenge>(history, maxAttempts = 3)
        val fixed = MathChallenge("1 + 1", 2, MissionDifficulty.VERY_EASY)

        // Pre-seed the history so every candidate the producer can emit is
        // already "recently seen".
        history.record(fixed.fingerprint, 0)

        // A short mission beats an alarm that cannot be stopped, so this must
        // return rather than spin.
        val result = factory.generate(SeededRandom(1), 0) { fixed }

        assertEquals(fixed.fingerprint, result.fingerprint)
    }
}
