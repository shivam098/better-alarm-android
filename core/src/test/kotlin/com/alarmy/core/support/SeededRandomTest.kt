package com.alarmy.core.support

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeededRandomTest {

    @Test
    fun `the same seed always produces the same stream`() {
        val a = SeededRandom(12345)
        val b = SeededRandom(12345)

        repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `different seeds diverge`() {
        val a = SeededRandom(1)
        val b = SeededRandom(2)

        val first = List(50) { a.nextLong() }
        val second = List(50) { b.nextLong() }

        assertTrue(first != second)
    }

    @Test
    fun `a zero seed does not collapse the generator`() {
        val random = SeededRandom(0)
        val values = List(50) { random.nextLong() }

        assertTrue(values.toSet().size > 40, "a degenerate state would repeat")
    }

    @Test
    fun `bounded integers stay inside the bound and are never negative`() {
        val random = SeededRandom(99)
        repeat(10_000) {
            val value = random.nextInt(10)
            assertTrue(value in 0..9, "got $value")
        }
    }

    @Test
    fun `range integers are inclusive at both ends`() {
        val random = SeededRandom(7)
        val seen = mutableSetOf<Int>()
        repeat(10_000) { seen += random.nextInt(3..5) }

        assertEquals(setOf(3, 4, 5), seen)
    }

    @Test
    fun `a single-value range always yields that value`() {
        val random = SeededRandom(7)
        repeat(100) { assertEquals(4, random.nextInt(4..4)) }
    }

    @Test
    fun `booleans are not obviously biased`() {
        val random = SeededRandom(2024)
        val trueCount = (0 until 10_000).count { random.nextBoolean() }

        assertTrue(trueCount in 4700..5300, "got $trueCount heads in 10000")
    }

    @Test
    fun `bounded integers are reasonably uniform`() {
        val random = SeededRandom(31337)
        val counts = IntArray(10)
        repeat(100_000) { counts[random.nextInt(10)] += 1 }

        counts.forEachIndexed { index, count ->
            assertTrue(count in 9000..11000, "bucket $index had $count")
        }
    }

    @Test
    fun `picking returns a member of the list`() {
        val random = SeededRandom(5)
        val items = listOf("a", "b", "c")

        repeat(100) { assertTrue(random.pick(items) in items) }
    }

    @Test
    fun `shuffling is a permutation`() {
        val random = SeededRandom(5)
        val items = (1..20).toList()

        val shuffled = random.shuffled(items)

        assertEquals(items.size, shuffled.size)
        assertEquals(items.toSet(), shuffled.toSet())
    }

    @Test
    fun `shuffling actually reorders`() {
        val random = SeededRandom(5)
        val items = (1..20).toList()

        assertTrue(random.shuffled(items) != items)
    }

    @Test
    fun `an occurrence seed is reproducible but differs between mornings`() {
        val monday = SeededRandom.forOccurrence("alarm-1-20260511-0700")
        val mondayAgain = SeededRandom.forOccurrence("alarm-1-20260511-0700")
        val tuesday = SeededRandom.forOccurrence("alarm-1-20260512-0700")

        assertEquals(monday.nextLong(), mondayAgain.nextLong())
        assertTrue(
            SeededRandom.forOccurrence("alarm-1-20260511-0700").nextLong() != tuesday.nextLong()
        )
    }

    @Test
    fun `salting an occurrence seed gives independent streams`() {
        val key = "alarm-1-20260511-0700"
        val first = SeededRandom.forOccurrence(key, salt = 0).nextLong()
        val second = SeededRandom.forOccurrence(key, salt = 1).nextLong()

        assertTrue(first != second)
    }
}

class TimeSourceTest {

    private val start: Instant = Instant.parse("2026-05-11T07:00:00Z")

    @Test
    fun `a fixed time source does not move on its own`() {
        val source = FixedTimeSource(start)

        assertEquals(start, source.now())
        assertEquals(start, source.now())
    }

    @Test
    fun `a fixed time source can be advanced deterministically`() {
        val source = FixedTimeSource(start)
        source.advance(Duration.ofMinutes(9))

        assertEquals(start.plusSeconds(540), source.now())

        source.advanceSeconds(60)
        assertEquals(start.plusSeconds(600), source.now())
    }

    @Test
    fun `a fixed time source defaults to UTC so tests are portable`() {
        assertEquals(ZoneId.of("UTC"), FixedTimeSource(start).zone())
    }

    @Test
    fun `the system time source tracks the wall clock`() {
        val source = SystemTimeSource()

        val before = Instant.now()
        val reported = source.now()
        val after = Instant.now()

        assertTrue(!reported.isBefore(before) && !reported.isAfter(after))
    }
}
