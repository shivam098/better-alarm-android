package com.alarmy.core.mission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.PI
import kotlin.math.sin

class WalkingCounterTest {
    @Test fun `boot total is a baseline and batched deltas complete only the mission goal`() {
        val counter = WalkingCounter(10)
        assertEquals(MotionOutcome.Ignored, counter.cumulative(12_000.0, 10.0))
        assertEquals(MotionOutcome.Counted(4, 10), counter.cumulative(12_004.0, 12.0))
        assertEquals(MotionOutcome.Completed(10), counter.cumulative(12_010.0, 15.0))
        assertEquals(MotionOutcome.Ignored, counter.cumulative(12_011.0, 16.0))
    }

    @Test fun `source switch preserves accepted steps and establishes a fresh baseline`() {
        val counter = WalkingCounter(3)
        counter.step(1.0)
        counter.changeSource()
        assertEquals(MotionOutcome.Ignored, counter.cumulative(500.0, 2.0))
        assertEquals(MotionOutcome.Completed(3), counter.cumulative(502.0, 3.0))
    }

    @Test fun `unchanged counter readings do not shorten the interval of the next batch`() {
        val counter = WalkingCounter(10)
        counter.cumulative(100.0, 1.0)
        counter.cumulative(100.0, 4.9)
        assertEquals(MotionOutcome.Counted(8, 10), counter.cumulative(108.0, 5.0))
    }

    @Test fun `counter reset duplicates invalid readings and implausible deltas cannot complete`() {
        val counter = WalkingCounter(10)
        counter.cumulative(100.0, 1.0)
        assertEquals(MotionOutcome.Ignored, counter.cumulative(100.0, 2.0))
        assertTrue(counter.cumulative(200.0, 2.1) is MotionOutcome.Rejected)
        assertEquals(MotionOutcome.Ignored, counter.cumulative(1.0, 3.0))
        assertEquals(MotionOutcome.Ignored, counter.cumulative(Double.NaN, 3.1))
        assertEquals(MotionOutcome.Ignored, counter.cumulative(2.0, 3.0))
        assertEquals(MotionOutcome.Counted(1, 10), counter.cumulative(2.0, 4.0))
    }

    @Test fun `cadence boundary accepts walking and rejects fast motion without erasing progress`() {
        val counter = WalkingCounter(3)
        counter.step(0.0)
        assertTrue(counter.step(0.27) is MotionOutcome.Rejected)
        assertEquals(1, counter.count)
        assertEquals(MotionOutcome.Counted(2, 3), counter.step(0.55))
        assertEquals(MotionOutcome.Completed(3), counter.step(3.0))
    }

    @Test fun `synthetic walking peaks reach configured target but stationary noise does not count`() {
        val peaks = WalkingPeakDetector()
        val counter = WalkingCounter(20)
        for (i in 0..100) {
            val time = i * 0.02
            if (peaks.sample(9.81 + 0.1 * sin(time * 40), time)) counter.step(time)
        }
        assertEquals(0, counter.count)
        var completed = false
        for (i in 101..700) {
            val time = i * 0.02
            if (peaks.sample(9.81 + 2.0 * sin(2 * PI * 2 * time), time)) {
                if (counter.step(time) is MotionOutcome.Completed) completed = true
            }
        }
        assertTrue(completed)
        assertEquals(20, counter.count)
    }
}
