package com.alarmy.core.mission

import com.alarmy.core.support.SeededRandom

/**
 * Remembers recently-seen challenges so the same problem does not reappear
 * morning after morning.
 *
 * Repetition is how a mission stops working: a user who sees "7 x 8" every day
 * stops solving it and starts recalling it, which is exactly the half-asleep
 * autopilot the mission exists to interrupt.
 */
class RecentChallengeHistory(
    private val retentionDays: Int = 30,
    private val maxEntries: Int = 2000
) {
    private val seen = LinkedHashMap<String, Long>()

    fun record(fingerprint: String, nowMillis: Long) {
        seen.remove(fingerprint)
        seen[fingerprint] = nowMillis
        prune(nowMillis)
    }

    fun hasSeen(fingerprint: String, nowMillis: Long): Boolean {
        val recordedAt = seen[fingerprint] ?: return false
        return nowMillis - recordedAt <= retentionDays * 86_400_000L
    }

    fun size(): Int = seen.size

    fun clear() = seen.clear()

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - retentionDays * 86_400_000L
        val expired = seen.entries.filter { it.value < cutoff }.map { it.key }
        expired.forEach { seen.remove(it) }

        // LinkedHashMap preserves insertion order, so the oldest entries are
        // first — dropping from the front evicts least-recently-recorded.
        while (seen.size > maxEntries) {
            val oldest = seen.keys.firstOrNull() ?: break
            seen.remove(oldest)
        }
    }
}

/**
 * Wraps a generator so it produces a challenge the user has not seen recently.
 *
 * After [maxAttempts] tries it gives up and returns the last candidate rather
 * than looping. A slightly familiar problem is a far better failure mode than a
 * mission that never appears, so the alarm always has something to show.
 */
class UniqueChallengeFactory<T : MissionChallenge>(
    private val history: RecentChallengeHistory,
    private val maxAttempts: Int = 24
) {

    fun generate(
        random: SeededRandom,
        nowMillis: Long,
        producer: (SeededRandom) -> T
    ): T {
        var candidate = producer(random)
        var attempts = 1

        while (history.hasSeen(candidate.fingerprint, nowMillis) && attempts < maxAttempts) {
            candidate = producer(random)
            attempts += 1
        }

        history.record(candidate.fingerprint, nowMillis)
        return candidate
    }
}
