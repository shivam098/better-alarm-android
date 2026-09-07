package com.alarmy.core.mission

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One accelerometer reading.
 *
 * [timestampSeconds] is monotonic seconds since an arbitrary origin, not wall
 * clock — the analysis only ever uses differences, so an NTP correction or a
 * user changing the clock mid-mission cannot corrupt it.
 */
data class MotionSample(
    val x: Double,
    val y: Double,
    val z: Double,
    val timestampSeconds: Double
) {
    /** Vector magnitude including gravity, in the sensor's own units (m/s²). */
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
}

/** Small statistics helpers used by the motion validators. */
object MotionStatistics {

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        val variance = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance)
    }

    /**
     * Standard deviation as a fraction of the mean.
     *
     * This is the core anti-cheat signal. Human movement is irregular: even
     * someone deliberately shaking at a steady rhythm varies by 10–30% between
     * strokes. A phone taped to a washing machine, strapped to a fan, or rocked
     * by any other machine produces intervals that are near-identical, so a
     * coefficient of variation close to zero is strong evidence that nobody got
     * out of bed.
     */
    fun coefficientOfVariation(values: List<Double>): Double {
        val m = mean(values)
        if (abs(m) < 1e-9) return 0.0
        return standardDeviation(values) / abs(m)
    }
}

/** Result of feeding one event to a motion validator. */
sealed interface MotionOutcome {
    /** Accepted. [count] is the running total, [target] the goal. */
    data class Counted(val count: Int, val target: Int) : MotionOutcome

    /** Ignored — real but not a countable event (e.g. a partial squat). */
    data object Ignored : MotionOutcome

    /** Rejected as implausible; the count has been reset. */
    data class Rejected(val reason: MissionFailureReason) : MotionOutcome

    data class Completed(val count: Int) : MotionOutcome
}
