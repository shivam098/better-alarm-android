package com.alarmy.core.support

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Injectable clock.
 *
 * Every piece of time-dependent logic in this module takes one of these instead
 * of calling `Instant.now()`, which is what lets the tests place themselves on a
 * daylight-saving boundary or three weeks into a streak.
 */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
}

class SystemTimeSource(private val zone: ZoneId = ZoneId.systemDefault()) : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = zone
}

/** Test double. Time only moves when the test moves it. */
class FixedTimeSource(
    var instant: Instant,
    private val zone: ZoneId = ZoneId.of("UTC")
) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zone

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}
