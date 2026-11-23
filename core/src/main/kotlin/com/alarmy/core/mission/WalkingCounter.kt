package com.alarmy.core.mission

import kotlin.math.floor

/** Mission-local progress shared across sequential (never concurrent) sources. */
class WalkingCounter(private val target: Int) {
    private var lastCounter: Double? = null
    private var lastTime: Double? = null
    private val cadence = StepValidator(target = Int.MAX_VALUE)
    var count = 0
        private set

    fun changeSource() {
        lastCounter = null
        lastTime = null
        cadence.reset()
    }

    fun step(seconds: Double): MotionOutcome {
        if (count >= target) return MotionOutcome.Ignored
        return when (val outcome = cadence.register(seconds)) {
            is MotionOutcome.Counted -> add(1)
            is MotionOutcome.Rejected -> outcome
            else -> MotionOutcome.Ignored
        }
    }

    /** Counter values are cumulative since boot; the first reading is only a baseline. */
    fun cumulative(value: Double, seconds: Double): MotionOutcome {
        if (count >= target || !value.isFinite() || value < 0 || !seconds.isFinite()) return MotionOutcome.Ignored
        val previous = lastCounter
        val time = lastTime
        if (time != null && seconds <= time) return MotionOutcome.Ignored
        if (previous == null || time == null || value < previous) {
            lastCounter = value
            lastTime = seconds
            return MotionOutcome.Ignored
        }
        val delta = floor(value - previous).toInt()
        if (delta == 0) return MotionOutcome.Ignored
        lastCounter = value
        lastTime = seconds
        // Batched readings have no individual step timestamps. Validate the
        // whole delta, not invented zero-gap or evenly spaced detector events.
        if (delta > floor((seconds - time) / 0.28).toInt() + 1) {
            return MotionOutcome.Rejected(MissionFailureReason.IMPLAUSIBLE_MOTION)
        }
        return add(delta)
    }

    private fun add(steps: Int): MotionOutcome {
        count = (count.toLong() + steps).coerceAtMost(target.toLong()).toInt()
        return if (count >= target) MotionOutcome.Completed(count) else MotionOutcome.Counted(count, target)
    }
}

/** Foreground-only, orientation-independent acceleration peak detector. */
class WalkingPeakDetector {
    private var gravity: Double? = null
    private var previousTime: Double? = null
    private var startedAt = 0.0
    private var above = false

    fun sample(magnitude: Double, seconds: Double): Boolean {
        if (!magnitude.isFinite() || !seconds.isFinite()) return false
        val previous = previousTime
        if (previous != null && seconds <= previous) return false
        previousTime = seconds
        val baseline = gravity
        if (baseline == null) {
            gravity = magnitude
            startedAt = seconds
            return false
        }
        val alpha = (seconds - (previous ?: seconds)).coerceIn(0.0, 0.1) / 0.8
        gravity = baseline + alpha * (magnitude - baseline)
        val acceleration = magnitude - baseline
        if (acceleration < 0.2) above = false
        if (seconds - startedAt < 0.5 || above || acceleration < 1.2) return false
        above = true
        return true
    }
}
