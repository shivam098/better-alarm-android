package com.alarmy.core.mission

/**
 * Counts shakes, rejecting motion that is too regular to be human.
 *
 * The check is on the *intervals between* shakes rather than their magnitude,
 * which matters: a machine can easily produce a convincingly large
 * acceleration, but it cannot help producing it on a metronome. Requiring
 * natural variation is a far better discriminator than requiring vigour, and it
 * does not punish someone who simply shakes gently.
 *
 * A rejection resets the count *and clears the interval history*. Clearing is
 * not incidental — without it the very next sample would still see a
 * near-perfect run and reject again, trapping an honest user in a loop they
 * could not escape.
 */
class ShakeCounter(
    private val target: Int = 30,
    /** Intervals needed before periodicity can be judged at all. */
    private val minIntervalsForAnalysis: Int = 8,
    /** Below this coefficient of variation, motion is treated as mechanical. */
    private val periodicityThreshold: Double = 0.08
) {
    private val intervals = mutableListOf<Double>()
    private var lastTimestamp: Double? = null

    var count: Int = 0
        private set

    var rejections: Int = 0
        private set

    /** Feeds one detected shake event. */
    fun register(timestampSeconds: Double): MotionOutcome {
        lastTimestamp?.let { previous ->
            val delta = timestampSeconds - previous
            // Ignore non-monotonic or duplicate timestamps outright.
            if (delta > 0) intervals += delta
        }
        lastTimestamp = timestampSeconds

        if (intervals.size >= minIntervalsForAnalysis) {
            val recent = intervals.takeLast(minIntervalsForAnalysis)
            if (MotionStatistics.coefficientOfVariation(recent) < periodicityThreshold) {
                count = 0
                rejections += 1
                intervals.clear()
                return MotionOutcome.Rejected(MissionFailureReason.IMPLAUSIBLE_MOTION)
            }
        }

        count += 1
        return if (count >= target) MotionOutcome.Completed(count)
        else MotionOutcome.Counted(count, target)
    }

    fun reset() {
        count = 0
        intervals.clear()
        lastTimestamp = null
    }
}

/**
 * Counts steps, rejecting cadences a walking human cannot produce.
 *
 * Two filters, aimed at two different cheats:
 *
 *  * **Cadence bounds.** Human walking sits between roughly 0.5 and 3.5 steps
 *    per second. Anything faster is someone jiggling the phone in bed; the
 *    bounds are deliberately generous at the slow end so a genuinely slow or
 *    unsteady walker is never called a cheat.
 *  * **Periodicity.** As with shaking, a machine's regularity gives it away.
 *    The threshold here is lower than the shake counter's, because walking
 *    genuinely *is* more rhythmic than shaking, and a false accusation is worse
 *    than a missed one.
 */
class StepValidator(
    private val target: Int = 30,
    private val minStepIntervalSeconds: Double = 0.28,
    private val maxStepIntervalSeconds: Double = 2.0,
    private val minIntervalsForAnalysis: Int = 10,
    private val periodicityThreshold: Double = 0.04
) {
    private val intervals = mutableListOf<Double>()
    private var lastTimestamp: Double? = null

    var count: Int = 0
        private set

    fun register(timestampSeconds: Double): MotionOutcome {
        val previous = lastTimestamp
        lastTimestamp = timestampSeconds

        if (previous == null) {
            count += 1
            return outcome()
        }

        val delta = timestampSeconds - previous
        if (delta <= 0) return MotionOutcome.Ignored

        // Too fast to be walking — almost certainly shaking.
        if (delta < minStepIntervalSeconds) {
            count = 0
            intervals.clear()
            return MotionOutcome.Rejected(MissionFailureReason.IMPLAUSIBLE_MOTION)
        }

        // A long pause is fine; it just starts a fresh cadence run.
        if (delta > maxStepIntervalSeconds) {
            intervals.clear()
            count += 1
            return outcome()
        }

        intervals += delta

        if (intervals.size >= minIntervalsForAnalysis) {
            val recent = intervals.takeLast(minIntervalsForAnalysis)
            if (MotionStatistics.coefficientOfVariation(recent) < periodicityThreshold) {
                count = 0
                intervals.clear()
                return MotionOutcome.Rejected(MissionFailureReason.IMPLAUSIBLE_MOTION)
            }
        }

        count += 1
        return outcome()
    }

    private fun outcome(): MotionOutcome =
        if (count >= target) MotionOutcome.Completed(count)
        else MotionOutcome.Counted(count, target)

    fun reset() {
        count = 0
        intervals.clear()
        lastTimestamp = null
    }
}

/**
 * Counts squats from the phone's inclination.
 *
 * A repetition requires the full travel — down past [downAngle], back up past
 * [upAngle] — completed in a plausible amount of time. The angle is whatever
 * the platform layer derives from the accelerometer (degrees, ~180 when
 * upright, falling as the user descends), which keeps this class free of any
 * Android sensor types and therefore testable.
 *
 * The timing bounds are what stop the obvious cheat. Without [minRepSeconds],
 * rocking the phone back and forth in one hand counts as fast as the arm can
 * move; a real squat cannot be completed in under about four tenths of a
 * second. [maxRepSeconds] closes the opposite hole, where the phone is slowly
 * lowered and raised without anyone squatting at all.
 */
class SquatRepCounter(
    private val target: Int = 10,
    private val downAngle: Double = 110.0,
    private val upAngle: Double = 150.0,
    private val minRepSeconds: Double = 0.4,
    private val maxRepSeconds: Double = 8.0
) {
    private enum class Phase { UP, DESCENDING, DOWN }

    private var phase = Phase.UP
    private var downAtSeconds: Double? = null

    var count: Int = 0
        private set

    var rejections: Int = 0
        private set

    /** Feeds one inclination reading, in degrees. */
    fun register(angleDegrees: Double, timestampSeconds: Double): MotionOutcome {
        when (phase) {
            Phase.UP -> {
                if (angleDegrees <= downAngle) {
                    phase = Phase.DOWN
                    downAtSeconds = timestampSeconds
                } else if (angleDegrees < upAngle) {
                    phase = Phase.DESCENDING
                }
            }

            Phase.DESCENDING -> {
                if (angleDegrees <= downAngle) {
                    phase = Phase.DOWN
                    downAtSeconds = timestampSeconds
                } else if (angleDegrees >= upAngle) {
                    // Came back up without ever going deep enough: a partial
                    // dip, which is not a squat.
                    phase = Phase.UP
                    downAtSeconds = null
                }
            }

            Phase.DOWN -> {
                if (angleDegrees >= upAngle) {
                    val startedAt = downAtSeconds
                    phase = Phase.UP
                    downAtSeconds = null

                    if (startedAt != null) {
                        val duration = timestampSeconds - startedAt
                        if (duration < minRepSeconds) {
                            rejections += 1
                            return MotionOutcome.Rejected(
                                MissionFailureReason.IMPLAUSIBLE_MOTION
                            )
                        }
                        if (duration > maxRepSeconds) return MotionOutcome.Ignored

                        count += 1
                        return if (count >= target) MotionOutcome.Completed(count)
                        else MotionOutcome.Counted(count, target)
                    }
                }
            }
        }
        return MotionOutcome.Ignored
    }

    fun reset() {
        count = 0
        phase = Phase.UP
        downAtSeconds = null
    }
}
